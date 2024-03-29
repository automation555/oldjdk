/*
 * Copyright (c) 2001, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#include "precompiled.hpp"
#include "gc/shared/ptrQueue.hpp"
#include "logging/log.hpp"
#include "memory/allocation.hpp"
#include "memory/allocation.inline.hpp"
#include "runtime/atomic.hpp"
#include "runtime/mutex.hpp"
#include "runtime/mutexLocker.hpp"
#include "runtime/thread.inline.hpp"
#include "utilities/globalCounter.inline.hpp"

#include <new>

PtrQueue::PtrQueue(PtrQueueSet* qset) :
  _index(0),
  _capacity_in_bytes(index_to_byte_index(qset->buffer_size())),
  _buf(NULL)
{}

PtrQueue::~PtrQueue() {
  assert(_buf == NULL, "queue must be flushed before delete");
}

BufferNode* BufferNode::allocate(size_t size) {
  size_t byte_size = size * sizeof(void*);
  void* data = NEW_C_HEAP_ARRAY(char, buffer_offset() + byte_size, mtGC);
  return new (data) BufferNode;
}

void BufferNode::deallocate(BufferNode* node) {
  node->~BufferNode();
  FREE_C_HEAP_ARRAY(char, node);
}

BufferNode::Allocator::PendingList::PendingList() :
  _tail(nullptr), _head(nullptr), _count(0) {}

BufferNode::Allocator::PendingList::~PendingList() {
  delete_list(Atomic::load(&_head));
}

size_t BufferNode::Allocator::PendingList::add(BufferNode* node) {
  assert(node->next() == nullptr, "precondition");
  BufferNode* old_head = Atomic::xchg(&_head, node);
  if (old_head != nullptr) {
    node->set_next(old_head);
  } else {
    assert(_tail == nullptr, "invariant");
    _tail = node;
  }
  return Atomic::add(&_count, size_t(1));
}

BufferNodeList BufferNode::Allocator::PendingList::take_all() {
  BufferNodeList result{Atomic::load(&_head), _tail, Atomic::load(&_count)};
  Atomic::store(&_head, (BufferNode*)nullptr);
  _tail = nullptr;
  Atomic::store(&_count, size_t(0));
  return result;
}

BufferNode::Allocator::Allocator(const char* name, size_t buffer_size) :
  _buffer_size(buffer_size),
  _pending_lists(),
  _active_pending_list(0),
  _free_list(),
  _free_count(0),
  _transfer_lock(false)
{
  strncpy(_name, name, sizeof(_name) - 1);
  _name[sizeof(_name) - 1] = '\0';
}

BufferNode::Allocator::~Allocator() {
  delete_list(_free_list.pop_all());
}

void BufferNode::Allocator::delete_list(BufferNode* list) {
  while (list != NULL) {
    BufferNode* next = list->next();
    DEBUG_ONLY(list->set_next(NULL);)
    BufferNode::deallocate(list);
    list = next;
  }
}

size_t BufferNode::Allocator::free_count() const {
  return Atomic::load(&_free_count);
}

BufferNode* BufferNode::Allocator::allocate() {
  BufferNode* node;
  {
    // Protect against ABA; see release().
    GlobalCounter::CriticalSection cs(Thread::current());
    node = _free_list.pop();
  }
  if (node == NULL) {
    node = BufferNode::allocate(_buffer_size);
  } else {
    // Decrement count after getting buffer from free list.  This, along
    // with incrementing count before adding to free list, ensures count
    // never underflows.
    size_t count = Atomic::sub(&_free_count, 1u);
    assert((count + 1) != 0, "_free_count underflow");
  }
  return node;
}

// To solve the ABA problem for lock-free stack pop, allocate does the
// pop inside a critical section, and release synchronizes on the
// critical sections before adding to the _free_list.  But we don't
// want to make every release have to do a synchronize.  Instead, we
// initially place released nodes on the pending list, and transfer
// them to the _free_list in batches.  Only one transfer at a time is
// permitted, with a lock bit to control access to that phase.  While
// a transfer is in progress, other threads might be adding other nodes
// to the pending list, to be dealt with by some later transfer.
void BufferNode::Allocator::release(BufferNode* node) {
  assert(node != NULL, "precondition");
  assert(node->next() == NULL, "precondition");

  // Desired minimum transfer batch size.  There is relatively little
  // importance to the specific number.  It shouldn't be too big, else
  // we're wasting space when the release rate is low.  If the release
  // rate is high, we might accumulate more than this before being
  // able to start a new transfer, but that's okay.  Also note that
  // the allocation rate and the release rate are going to be fairly
  // similar, due to how the buffers are used.
  const size_t trigger_transfer = 10;

  // The pending list is double-buffered.  Add node to the currently active
  // pending list, within a critical section so a transfer will wait until
  // we're done with what might be the pending list to be transferred.
  {
    GlobalCounter::CriticalSection cs(Thread::current());
    uint index = Atomic::load_acquire(&_active_pending_list);
    size_t count = _pending_lists[index].add(node);
    if (count <= trigger_transfer) return;
  }
  // Attempt transfer when number pending exceeds the transfer threshold.
  try_transfer_pending();
}

// Try to transfer nodes from the pending list to _free_list, with a
// synchronization delay for any in-progress pops from the _free_list,
// to solve ABA there.  Return true if performed a (possibly empty)
// transfer, false if blocked from doing so by some other thread's
// in-progress transfer.
bool BufferNode::Allocator::try_transfer_pending() {
  // Attempt to claim the lock.
  if (Atomic::load(&_transfer_lock) || // Skip CAS if likely to fail.
      Atomic::cmpxchg(&_transfer_lock, false, true)) {
    return false;
  }
  // Have the lock; perform the transfer.

  // Change which pending list is active.  Don't need an atomic RMW since
  // we have the lock and we're the only writer.
  uint index = Atomic::load(&_active_pending_list);
  uint new_active = (index + 1) % ARRAY_SIZE(_pending_lists);
  Atomic::release_store(&_active_pending_list, new_active);

  // Wait for all critical sections in the buffer life-cycle to complete.
  // This includes _free_list pops and adding to the now inactive pending
  // list.
  GlobalCounter::write_synchronize();

  // Transfer the inactive pending list to _free_list.
  BufferNodeList transfer_list = _pending_lists[index].take_all();
  size_t count = transfer_list._entry_count;
  if (count > 0) {
    // Update count first so no underflow in allocate().
    Atomic::add(&_free_count, count);
    _free_list.prepend(*transfer_list._head, *transfer_list._tail);
    log_trace(gc, ptrqueue, freelist)
             ("Transferred %s pending to free: %zu", name(), count);
  }
  Atomic::release_store(&_transfer_lock, false);
  return true;
}

size_t BufferNode::Allocator::reduce_free_list(size_t remove_goal) {
  try_transfer_pending();
  size_t removed = 0;
  for ( ; removed < remove_goal; ++removed) {
    BufferNode* node = _free_list.pop();
    if (node == NULL) break;
    BufferNode::deallocate(node);
  }
  size_t new_count = Atomic::sub(&_free_count, removed);
  log_debug(gc, ptrqueue, freelist)
           ("Reduced %s free list by " SIZE_FORMAT " to " SIZE_FORMAT,
            name(), removed, new_count);
  return removed;
}

PtrQueueSet::PtrQueueSet(BufferNode::Allocator* allocator) :
  _allocator(allocator)
{}

PtrQueueSet::~PtrQueueSet() {}

void PtrQueueSet::reset_queue(PtrQueue& queue) {
  if (queue.buffer() != nullptr) {
    queue.set_index(buffer_size());
  }
}

void PtrQueueSet::flush_queue(PtrQueue& queue) {
  void** buffer = queue.buffer();
  if (buffer != nullptr) {
    size_t index = queue.index();
    queue.set_buffer(nullptr);
    queue.set_index(0);
    BufferNode* node = BufferNode::make_node_from_buffer(buffer, index);
    if (index == buffer_size()) {
      deallocate_buffer(node);
    } else {
      enqueue_completed_buffer(node);
    }
  }
}

bool PtrQueueSet::try_enqueue(PtrQueue& queue, void* value) {
  size_t index = queue.index();
  if (index == 0) return false;
  void** buffer = queue.buffer();
  assert(buffer != nullptr, "no buffer but non-zero index");
  buffer[--index] = value;
  queue.set_index(index);
  return true;
}

void PtrQueueSet::retry_enqueue(PtrQueue& queue, void* value) {
  assert(queue.index() != 0, "precondition");
  assert(queue.buffer() != nullptr, "precondition");
  size_t index = queue.index();
  queue.buffer()[--index] = value;
  queue.set_index(index);
}

BufferNode* PtrQueueSet::exchange_buffer_with_new(PtrQueue& queue) {
  BufferNode* node = nullptr;
  void** buffer = queue.buffer();
  if (buffer != nullptr) {
    node = BufferNode::make_node_from_buffer(buffer, queue.index());
  }
  install_new_buffer(queue);
  return node;
}

void PtrQueueSet::install_new_buffer(PtrQueue& queue) {
  queue.set_buffer(allocate_buffer());
  queue.set_index(buffer_size());
}

void** PtrQueueSet::allocate_buffer() {
  BufferNode* node = _allocator->allocate();
  return BufferNode::make_buffer_from_node(node);
}

void PtrQueueSet::deallocate_buffer(BufferNode* node) {
  _allocator->release(node);
}
