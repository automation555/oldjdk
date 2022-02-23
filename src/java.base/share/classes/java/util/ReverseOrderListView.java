/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
 */

package java.util;

import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A reverse-order view of a List.
 *
 * TODO: RandomAccess
 * @author smarks 2020-04-27
 */
class ReverseOrderListView<E> implements List<E> {

    final List<E> base;

    public static <T> List<T> of(List<T> list) {
        if (list instanceof ReverseOrderListView) {
            return ((ReverseOrderListView<T>)list).base;
        } else {
            return new ReverseOrderListView<>(list);
        }
    }

    ReverseOrderListView(List<E> list) {
        this.base = list;
    }

    class DescendingIterator implements Iterator<E> {
        final ListIterator<E> it = base.listIterator(base.size());
        public boolean hasNext() { return it.hasPrevious(); }
        public E next() { return it.previous(); }
        public void remove() {
            it.remove();
            // TODO - make sure ListIterator is positioned correctly afterward
        }
    }

    class DescendingListIterator implements ListIterator<E> {
        final ListIterator<E> it;

        DescendingListIterator(int size, int pos) {
            if (pos < 0 || pos > size)
                throw new IndexOutOfBoundsException();
            it = base.listIterator(size - pos);
        }

        public boolean hasNext() {
            return it.hasPrevious();
        }

        public E next() {
            return it.previous();
        }

        public boolean hasPrevious() {
            return it.hasNext();
        }

        public E previous() {
            return it.next();
        }

        public int nextIndex() {
            return base.size() - it.nextIndex();
        }

        public int previousIndex() {
            return nextIndex() - 1;
        }

        public void remove() {
            it.remove();
        }

        public void set(E e) {
            it.set(e);
        }

        public void add(E e) {
            it.add(e);
            it.previous();
        }
    }

    // ========== Iterable ==========

    public void forEach(Consumer<? super E> action) {
        for (E e : this)
            action.accept(e);
    }

    public Iterator<E> iterator() {
        return new DescendingIterator();
    }

    public Spliterator<E> spliterator() {
        // TODO can probably improve this
        return Spliterators.spliteratorUnknownSize(new DescendingIterator(), 0);
    }

    // ========== Collection ==========

    public boolean add(E e) {
        base.add(0, e);
        return true;
    }

    public boolean addAll(Collection<? extends E> c) {
        boolean modified = false;
        for (E e : c) {
            base.add(0, e);
            modified = true;
        }
        return modified;
    }

    public void clear() {
        base.clear();
    }

    public boolean contains(Object o) {
        return base.contains(o);
    }

    public boolean containsAll(Collection<?> c) {
        return base.containsAll(c);
    }

    // copied from AbstractList
    public boolean equals(Object o) {
        if (o == this)
            return true;
        if (!(o instanceof List))
            return false;

        ListIterator<E> e1 = listIterator();
        ListIterator<?> e2 = ((List<?>) o).listIterator();
        while (e1.hasNext() && e2.hasNext()) {
            E o1 = e1.next();
            Object o2 = e2.next();
            if (!(o1==null ? o2==null : o1.equals(o2)))
                return false;
        }
        return !(e1.hasNext() || e2.hasNext());
    }

    // copied from AbstractList
    public int hashCode() {
        int hashCode = 1;
        for (E e : this)
            hashCode = 31*hashCode + (e==null ? 0 : e.hashCode());
        return hashCode;
    }

    public boolean isEmpty() {
        return base.isEmpty();
    }

    public Stream<E> parallelStream() {
        return StreamSupport.stream(spliterator(), true);
    }

    // copied from AbstractCollection
    public boolean remove(Object o) {
        Iterator<E> it = iterator();
        if (o==null) {
            while (it.hasNext()) {
                if (it.next()==null) {
                    it.remove();
                    return true;
                }
            }
        } else {
            while (it.hasNext()) {
                if (o.equals(it.next())) {
                    it.remove();
                    return true;
                }
            }
        }
        return false;
    }

    // copied from AbstractCollection
    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        boolean modified = false;
        Iterator<?> it = iterator();
        while (it.hasNext()) {
            if (c.contains(it.next())) {
                it.remove();
                modified = true;
            }
        }
        return modified;
    }

    // copied from AbstractCollection
    public boolean retainAll(Collection<?> c) {
        Objects.requireNonNull(c);
        boolean modified = false;
        Iterator<E> it = iterator();
        while (it.hasNext()) {
            if (!c.contains(it.next())) {
                it.remove();
                modified = true;
            }
        }
        return modified;
    }

    public int size() {
        return base.size();
    }

    public Stream<E> stream() {
        return StreamSupport.stream(spliterator(), false);
    }

    private <T> T[] arrayReverse(T[] a) {
        int limit = a.length / 2;
        for (int i = 0; i < limit; i++) {
            int r = a.length - 1 - i;
            T t = a[i];
            a[i] = a[r];
            a[r] = t;
        }
        return a;
    }

    public Object[] toArray() {
        return arrayReverse(base.toArray());
    }

    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        // TODO can probably optimize this
        return toArray(i -> (T[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), i));
    }

    public <T> T[] toArray(IntFunction<T[]> generator) {
        return arrayReverse(base.toArray(generator));
    }

    // copied from AbstractCollection
    public String toString() {
        Iterator<E> it = iterator();
        if (! it.hasNext())
            return "[]";

        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (;;) {
            E e = it.next();
            sb.append(e == this ? "(this Collection)" : e);
            if (! it.hasNext())
                return sb.append(']').toString();
            sb.append(',').append(' ');
        }
    }

    // ========== List ==========

    public void add(int index, E element) {
        base.add(base.size() - index, element);
    }

    public boolean addAll(int index, Collection<? extends E> c) {
        boolean modified = false;
        int i = base.size() - index;
        for (E e : c) {
            base.add(i, e);
            modified = true;
        }
        return modified;
    }

    public E get(int i) {
        return base.get(base.size() - i - 1);
    }

    public int indexOf(Object o) {
        int i = base.lastIndexOf(o);
        return i == -1 ? -1 : base.size() - i - 1;
    }

    public int lastIndexOf(Object o) {
        int i = base.indexOf(o);
        return i == -1 ? -1 : base.size() - i - 1;
    }

    public ListIterator<E> listIterator() {
        return new DescendingListIterator(base.size(), 0);
    }

    public ListIterator<E> listIterator(int index) {
        return new DescendingListIterator(base.size(), index);
    }

    public E remove(int index) {
        return base.remove(base.size() - index - 1);
    }

    public boolean removeIf(Predicate<? super E> filter) {
        return base.removeIf(filter);
    }

    public void replaceAll(UnaryOperator<E> operator) {
        base.replaceAll(operator);
    }

    public void sort(Comparator<? super E> c) {
        base.sort(Collections.reverseOrder(c));
    }

    public E set(int index, E element) {
        return base.set(base.size() - index - 1, element);
    }

    public List<E> subList(int fromIndex, int toIndex) {
        int size = base.size();
        return new ReverseOrderListView<>(base.subList(size - toIndex, size - fromIndex));
    }
}
