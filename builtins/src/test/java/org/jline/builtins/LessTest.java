/*
 * Copyright (c) 2026, the original author(s).
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.builtins;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LessTest {

    private static final String EVIL_PATTERN = "a*a*b";

    private static final String[] PATTERNS = {"(ba[rz])", "(x*)", "(r b)"};

    private static String evilLine() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            sb.append('a');
        }
        return sb.toString();
    }

    private static LineDisciplineTerminal terminal() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = new LineDisciplineTerminal("less", "xterm", output, StandardCharsets.UTF_8);
        terminal.setSize(new Size(80, 10));
        // Deliver '\r' as is, so that it validates the search pattern
        Attributes attrs = terminal.getAttributes();
        attrs.setInputFlag(Attributes.InputFlag.ICRNL, false);
        terminal.setAttributes(attrs);
        return terminal;
    }

    private static void type(LineDisciplineTerminal terminal, String keys) throws IOException {
        terminal.processInputBytes(keys.getBytes(StandardCharsets.UTF_8));
    }

    private static Source source(String... lines) {
        return source(Arrays.asList(lines));
    }

    private static Source source(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        return new Source.InputStreamSource(new ByteArrayInputStream(bytes), true, "test");
    }

    private static List<String> lines(String first, int count, String repeated, String last) {
        List<String> lines = new ArrayList<>();
        lines.add(first);
        lines.addAll(Collections.nCopies(count, repeated));
        lines.add(last);
        return lines;
    }

    private static Options options() {
        String[] argv = {"--ignorercfiles"};
        return Options.compile(Less.usage()).parse(argv);
    }

    /**
     * Less that records the status message shown by each redraw.
     */
    static class RecordingLess extends Less {
        final List<String> messages = new ArrayList<>();

        RecordingLess(LineDisciplineTerminal terminal) {
            super(terminal, Paths.get("."), options());
        }

        @Override
        boolean display(boolean oneScreen) throws IOException {
            boolean res = super.display(oneScreen);
            messages.add(message);
            return res;
        }
    }

    @Test
    @Timeout(20)
    public void testForwardSearchReDoS() throws Exception {
        LineDisciplineTerminal terminal = terminal();
        String evil = evilLine();
        type(terminal, "/" + EVIL_PATTERN + "\r");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        long start = System.nanoTime();
        less.run(source(lines("first", 7, evil, "last")));
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        String messages = String.valueOf(less.messages);
        assertTrue(less.messages.contains("Pattern search timed out"), messages);
        assertEquals(0, less.firstLineToDisplay);
        assertTrue(elapsedMs < 15000, "less took " + elapsedMs + " ms");
    }

    @Test
    @Timeout(20)
    public void testBackwardSearchReDoS() throws Exception {
        LineDisciplineTerminal terminal = terminal();
        String evil = evilLine();
        // Jump to the last line, then search backward
        type(terminal, "G");
        type(terminal, "?" + EVIL_PATTERN + "\r");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        long start = System.nanoTime();
        less.run(source(lines(evil, 11, evil, "last")));
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        String messages = String.valueOf(less.messages);
        assertTrue(less.messages.contains("Pattern search timed out"), messages);
        assertTrue(elapsedMs < 15000, "less took " + elapsedMs + " ms");
    }

    @Test
    @Timeout(20)
    public void testHighlightReDoS() throws Exception {
        LineDisciplineTerminal terminal = terminal();
        String evil = evilLine();
        // The pattern matches "last" so the search succeeds, but highlighting
        // the lines on screen would backtrack catastrophically
        type(terminal, "/a*a*b|last\r");
        type(terminal, "k");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        long start = System.nanoTime();
        List<String> content = new ArrayList<>(Arrays.asList("first", "last"));
        content.addAll(Collections.nCopies(8, evil));
        less.run(source(content));
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        String messages = String.valueOf(less.messages);
        assertTrue(less.messages.contains("Pattern highlighting timed out"), messages);
        assertTrue(elapsedMs < 15000, "less took " + elapsedMs + " ms");
    }

    @Test
    @Timeout(20)
    public void testDisplayPatternReDoS() throws Exception {
        LineDisciplineTerminal terminal = terminal();
        String evil = evilLine();
        // Only display the lines matching the pattern
        type(terminal, "&" + EVIL_PATTERN + "\r");
        type(terminal, "j");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        long start = System.nanoTime();
        less.run(source(lines("first", 12, evil, "last")));
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        String messages = String.valueOf(less.messages);
        assertTrue(less.messages.contains("Pattern filtering timed out"), messages);
        assertNull(less.displayPattern);
        assertTrue(elapsedMs < 15000, "less took " + elapsedMs + " ms");
    }

    @Test
    @Timeout(10)
    public void testDisplayPattern() throws Exception {
        LineDisciplineTerminal terminal = terminal();
        type(terminal, "&ba[rz]\r");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        less.run(source("foo", "bar", "qux", "baz"));

        assertEquals("ba[rz]", less.displayPattern);
        assertFalse(less.messages.contains("Pattern filtering timed out"));
    }

    @Test
    @Timeout(10)
    public void testSearch() throws Exception {
        LineDisciplineTerminal terminal = terminal();
        type(terminal, "/ba[rz]\r");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        less.run(source("foo", "qux", "bar", "baz"));

        assertEquals(2, less.firstLineToDisplay);
        assertFalse(less.messages.contains("Pattern not found"));
        assertFalse(less.messages.contains("Pattern search timed out"));
        assertFalse(less.messages.contains("Pattern highlighting timed out"));
    }

    @Test
    @Timeout(10)
    public void testSearchNotFound() throws Exception {
        LineDisciplineTerminal terminal = terminal();
        type(terminal, "/nothere\r");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        less.run(source("foo", "bar", "baz"));

        assertEquals(0, less.firstLineToDisplay);
        assertTrue(less.messages.contains("Pattern not found"));
    }

    @Test
    public void testStyleMatchesSameAsAttributedString() throws Exception {
        assertSameStyle("foo bar baz");
        assertSameStyle("\u001B[1mbold bar\u001B[0m and \u001B[32mgreen baz\u001B[0m");
        assertSameStyle("no match here");
        assertSameStyle("");
        assertSameStyle("barbarbar");
    }

    private static void assertSameStyle(String ansi) throws Exception {
        Class<?>[] types = {AttributedString.class, Pattern.class, AttributedStyle.class};
        Method styleMatches = Less.class.getDeclaredMethod("styleMatches", types);
        styleMatches.setAccessible(true);
        AttributedStyle inverse = AttributedStyle.DEFAULT.inverse();
        AttributedString line = AttributedString.fromAnsi(ansi);
        for (String regex : PATTERNS) {
            Pattern p = Pattern.compile(regex);
            AttributedString expected = line.styleMatches(p, inverse);
            Object result = styleMatches.invoke(null, line, p, inverse);
            AttributedString actual = (AttributedString) result;
            String msg = ansi + " / " + regex;
            assertEquals(expected, actual, msg);
            assertEquals(expected.toAnsi(), actual.toAnsi(), msg);
        }
    }
}
