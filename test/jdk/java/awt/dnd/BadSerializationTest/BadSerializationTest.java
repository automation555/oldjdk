/*
 * Copyright (c) 2014, 2018, Oracle and/or its affiliates. All rights reserved.
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
 */

/**
 * @test
 * @key headful
 * @bug 8030050 8039082
 * @summary Validate fields on DnD class deserialization
 */

import java.awt.Point;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.DragGestureRecognizer;
import java.awt.dnd.DragSource;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.stream.Stream;

import javax.swing.JPanel;

import static java.awt.dnd.DnDConstants.ACTION_COPY;

public class BadSerializationTest {

    private static final String[] badSerialized = new String[] {
            "badAction",
            "noEvents",
            "nullComponent",
            "nullDragSource",
            "nullOrigin"
    };

    private static final String goodSerialized = "good";

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("-write")) {
            // Creates the binary files for the test:
            // java --add-opens=java.desktop/java.awt.dnd=ALL-UNNAMED BadSerializationTest -write
            writeObjects();
        } else {
            String testSrc = System.getProperty("test.src") + File.separator;
            testReadObject(testSrc + goodSerialized, false);
            Stream.of(badSerialized).forEach(
                    file -> testReadObject(testSrc + file, true));
        }
    }

    private static void testReadObject(String filename, boolean expectException) {
        Exception exceptionCaught = null;
        try (FileInputStream fileInputStream = new FileInputStream(filename);
             ObjectInputStream ois = new ObjectInputStream(fileInputStream)) {
            ois.readObject();
        } catch (InvalidObjectException e) {
            exceptionCaught = e;
        } catch (IOException e) {
            throw new RuntimeException("FAILED: IOException", e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("FAILED: ClassNotFoundException", e);
        }
        if (exceptionCaught != null && !expectException) {
            throw new RuntimeException("FAILED: UnexpectedException", exceptionCaught);
        }
        if (exceptionCaught == null && expectException) {
            throw new RuntimeException("FAILED: Invalid object was created with no exception");
        }
    }

    /**
     * Creates the stubs for the test.
     */
    private static void writeObjects() throws Exception {
        ArrayList<InputEvent> evs = new ArrayList<>();
        evs.add(new KeyEvent(new JPanel(), 0, 0, 0, 0, 'a', 0));
        Point ori = new Point();

        write(createNoEvents(ori, evs), "noEvents");

        write(createNullComponent(ori, evs), "nullComponent");

        write(createBadAction(ori, evs), "badAction");

        write(createNullDragSource(ori, evs), "nullDragSource");

        write(createNullOrigin(ori, evs), "nullOrigin");

        write(new DragGestureEvent(new NothingNull(), ACTION_COPY, ori, evs),
              "good");
    }

    private static void write(Object obj, String file) throws Exception {
        try (FileOutputStream fis = new FileOutputStream(file);
             ObjectOutputStream ois = new ObjectOutputStream(fis)) {
            ois.writeObject(obj);
        }
    }

    public static DragGestureEvent createNoEvents(Point ori, ArrayList<InputEvent> evs) {
        DragGestureEvent noEvents = new DragGestureEvent(new NothingNull(), ACTION_COPY, ori, evs);
        setField(DragGestureEvent.class, noEvents, "events", new ArrayList<>());
        return noEvents;
    }

    public static DragGestureEvent createNullComponent(Point ori, ArrayList<InputEvent> evs) {
        DragGestureRecognizer dgr = new NothingNull();
        DragGestureEvent nullComponent = new DragGestureEvent(dgr, ACTION_COPY, ori, evs);
        setField(DragGestureRecognizer.class, dgr, "component", null);
        setField(DragGestureEvent.class, nullComponent, "component", null);
        return nullComponent;
    }

    public static DragGestureEvent createNullDragSource(Point ori, ArrayList<InputEvent> evs) {
        DragGestureRecognizer dgr = new NothingNull();
        DragGestureEvent nullDragSource = new DragGestureEvent(dgr, ACTION_COPY, ori, evs);
        setField(DragGestureRecognizer.class, dgr, "dragSource", null);
        setField(DragGestureEvent.class, nullDragSource, "dragSource", null);
        return nullDragSource;
    }

    public static DragGestureEvent createNullOrigin(Point ori, ArrayList<InputEvent> evs) {
        DragGestureEvent nullOrigin = new DragGestureEvent(new NothingNull(), ACTION_COPY, ori, evs);
        setField(DragGestureEvent.class, nullOrigin, "origin", null);
        return nullOrigin;
    }

    public static DragGestureEvent createBadAction(Point ori, ArrayList<InputEvent> evs) {
        DragGestureEvent badAction = new DragGestureEvent(new NothingNull(), ACTION_COPY, ori, evs);
        setField(DragGestureEvent.class, badAction, "action", 100);
        return badAction;
    }

    public static void setField(Class<?> clazz, Object instance, String fieldName, Object value) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(instance, value);
        } catch (ReflectiveOperationException e) {
            throw new LinkageError(e.getMessage(), e);
        }
    }

    public static final class NothingNull extends DragGestureRecognizer {

        public NothingNull() {
            super(new DragSource(), new JPanel());
        }

        protected void registerListeners() {
        }

        protected void unregisterListeners() {
        }
    }
}
