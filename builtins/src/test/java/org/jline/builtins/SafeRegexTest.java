/*
 * Copyright (c) 2026, the original author(s).
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.builtins;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SafeRegexTest {

    /** Catastrophic backtracking pattern: cubic on "aaaa..." (no 'b') */
    static final String EVIL_PATTERN = "a*a*b";

    static String evilInput() {
        return longString('a', 100000);
    }

    @Test
    public void testFindSameAsPlainMatcher() {
        Pattern p = Pattern.compile("b[a-z]+");
        String input = "foo bar baz qux bzz";
        List<String> expected = new ArrayList<>();
        Matcher plain = p.matcher(input);
        while (plain.find()) {
            expected.add(describe(plain));
        }
        List<String> actual = new ArrayList<>();
        Matcher safe = SafeRegex.matcher(p, input);
        while (safe.find()) {
            actual.add(describe(safe));
        }
        assertEquals(3, expected.size());
        assertEquals(expected, actual);
    }

    @Test
    public void testMatchesAndGroups() {
        Pattern p = Pattern.compile("(\\w+)=(\\d+)");
        Matcher m = SafeRegex.matcher(p, "key=42");
        assertTrue(m.matches());
        assertEquals("key", m.group(1));
        assertEquals("42", m.group(2));
        assertTrue(SafeRegex.matches(p, "key=42"));
        assertFalse(SafeRegex.matches(Pattern.compile("\\d+"), "abc"));
    }

    @Test
    public void testAttributedStringInput() {
        String ansi = "\u001B[1mhello\u001B[0m world";
        AttributedString line = AttributedString.fromAnsi(ansi);
        Matcher m = SafeRegex.matcher(Pattern.compile("wor"), line);
        assertTrue(m.find());
        assertEquals(6, m.start());
        assertEquals(9, m.end());
    }

    @Test
    public void testLongInputDoesNotTimeOut() {
        // Linear matching over a large input must not trip the deadline
        String input = longString('x', 200000) + "needle";
        Matcher m = SafeRegex.matcher(Pattern.compile("needle"), input);
        assertTrue(m.find());
        assertEquals(200000, m.start());
    }

    @Test
    public void testDeadlineStartsLazily() throws Exception {
        String input = longString('x', 10000) + "y";
        Matcher m = SafeRegex.matcher(Pattern.compile("x+y"), input, 100);
        // The timeout measures matching time, not the time since the matcher was created
        Thread.sleep(300);
        assertTrue(m.find());
    }

    @Test
    @Timeout(10)
    public void testCatastrophicBacktrackingTimesOut() {
        Pattern evil = Pattern.compile(EVIL_PATTERN);
        Matcher m = SafeRegex.matcher(evil, evilInput(), 200);
        long start = System.nanoTime();
        assertThrows(RegexTimeoutException.class, m::find);
        long elapsedMs = (System.nanoTime() - start) / 1000000;
        assertTrue(elapsedMs < 5000, "Timed out after " + elapsedMs + " ms");
    }

    @Test
    @Timeout(10)
    public void testCatastrophicBacktrackingTimesOutWithDefaultTimeout() {
        Pattern evil = Pattern.compile(EVIL_PATTERN);
        Matcher m = SafeRegex.matcher(evil, evilInput());
        long start = System.nanoTime();
        assertThrows(RegexTimeoutException.class, m::find);
        long elapsedMs = (System.nanoTime() - start) / 1000000;
        long timeout = SafeRegex.DEFAULT_TIMEOUT_MS;
        String msg = "Timed out after " + elapsedMs + " ms";
        assertTrue(elapsedMs >= timeout - 100, msg);
        assertTrue(elapsedMs < timeout + 3000, msg);
    }

    @Test
    @Timeout(10)
    public void testMatchesReturnsFalseOnTimeout() {
        Pattern evil = Pattern.compile(EVIL_PATTERN);
        long start = System.nanoTime();
        assertFalse(SafeRegex.matches(evil, evilInput()));
        long elapsedMs = (System.nanoTime() - start) / 1000000;
        long timeout = SafeRegex.DEFAULT_TIMEOUT_MS;
        assertTrue(elapsedMs < timeout + 3000, "Timed out after " + elapsedMs + " ms");
        assertTrue(SafeRegex.matches(Pattern.compile("a+"), evilInput()));
        assertFalse(SafeRegex.matches(Pattern.compile("a+!"), evilInput()));
    }

    @Test
    public void testSubSequenceDelegates() {
        Matcher m = SafeRegex.matcher(Pattern.compile("(b+)"), "aabbbcc");
        assertTrue(m.find());
        assertEquals("bbb", m.group(1));
        CharSequence seq = m.group(1);
        assertEquals(3, seq.length());
        assertEquals("bbb", seq.toString());
    }

    private static String describe(Matcher m) {
        return m.start() + "-" + m.end() + ":" + m.group();
    }

    private static String longString(char c, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
