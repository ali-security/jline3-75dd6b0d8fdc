/*
 * Copyright (c) 2002-2017, the original author(s).
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.builtins;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import org.jline.keymap.KeyMap;
import org.jline.terminal.Size;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NanoTest {

    @Test
    @Timeout(1)
    public void nanoBufferLineOverflow() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = new LineDisciplineTerminal("nano", "xterm", output, StandardCharsets.UTF_8);
        terminal.setSize(new Size(80, 25));
        for (int i = 0; i < 100; i++) {
            terminal.processInputByte(' ');
        }
        terminal.processInputByte(KeyMap.ctrl('X').getBytes()[0]);
        terminal.processInputByte('n');
        String[] argv = {"--ignorercfiles"};
        Nano nano = new Nano(
                terminal,
                Paths.get("target/test.txt"),
                Options.compile(Nano.usage()).parse(argv));
        nano.run();
    }

    private static Nano nanoWithLines(List<String> lines) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = new LineDisciplineTerminal("nano", "xterm", output, StandardCharsets.UTF_8);
        terminal.setSize(new Size(80, 25));
        String[] argv = {"--ignorercfiles"};
        Options opts = Options.compile(Nano.usage()).parse(argv);
        Nano nano = new Nano(terminal, Paths.get("target"), opts);
        nano.size.copy(terminal.getSize());
        nano.shortcuts = new LinkedHashMap<>();
        nano.shortcuts.put("^X", "Exit");
        nano.shortcuts.put("^W", "Where Is");
        nano.buffer = nano.new Buffer(null);
        nano.buffer.open();
        nano.buffer.lines = new ArrayList<>(lines);
        nano.buffer.computeAllOffsets();
        return nano;
    }

    private static List<String> lines(String first, int count, String repeated, String last) {
        List<String> lines = new ArrayList<>();
        lines.add(first);
        lines.addAll(Collections.nCopies(count, repeated));
        lines.add(last);
        return lines;
    }

    private static String evilLine() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            sb.append('a');
        }
        return sb.toString();
    }

    @Test
    @Timeout(20)
    public void testSearchReDoS() throws Exception {
        Nano nano = nanoWithLines(lines("first", 10, evilLine(), "last"));
        nano.searchTerm = "a*a*b";
        nano.searchRegexp = true;

        long start = System.nanoTime();
        nano.buffer.nextSearch();
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertNotNull(nano.message);
        assertTrue(nano.message.contains("timed out"), nano.message);
        assertEquals(0, nano.buffer.line);
        assertEquals(0, nano.buffer.column);
        assertTrue(elapsedMs < 10000, "search took " + elapsedMs + " ms");
    }

    @Test
    @Timeout(20)
    public void testBackwardSearchReDoS() throws Exception {
        Nano nano = nanoWithLines(lines("first", 8, evilLine(), "last"));
        nano.searchTerm = "a*a*b";
        nano.searchRegexp = true;
        nano.searchBackwards = true;

        long start = System.nanoTime();
        nano.buffer.nextSearch();
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertNotNull(nano.message);
        assertTrue(nano.message.contains("timed out"), nano.message);
        assertEquals(0, nano.buffer.line);
        assertTrue(elapsedMs < 10000, "search took " + elapsedMs + " ms");
    }

    @Test
    @Timeout(10)
    public void testSearch() throws Exception {
        Nano nano = nanoWithLines(Arrays.asList("foo", "bar baz", "qux"));
        nano.searchTerm = "ba[rz]";
        nano.searchRegexp = true;

        nano.buffer.nextSearch();
        assertEquals(1, nano.buffer.line);
        assertEquals(0, nano.buffer.offsetInLine + nano.buffer.column);
        assertEquals(3, nano.matchedLength);

        nano.buffer.nextSearch();
        assertEquals(1, nano.buffer.line);
        assertEquals(4, nano.buffer.offsetInLine + nano.buffer.column);

        nano.searchTerm = "nothere";
        nano.buffer.nextSearch();
        assertEquals("\"nothere\" not found", nano.message);
    }

    @Test
    @Timeout(10)
    public void testLiteralSearch() throws Exception {
        Nano nano = nanoWithLines(Arrays.asList("first", evilLine(), "x(a+)+$y"));
        // Without the regexp flag the term is matched literally
        nano.searchTerm = "(a+)+$";
        nano.searchRegexp = false;

        nano.buffer.nextSearch();
        assertEquals(2, nano.buffer.line);
        assertEquals(1, nano.buffer.offsetInLine + nano.buffer.column);
        assertEquals(6, nano.matchedLength);
    }
}
