package com.octant.pipeline.export;

import java.util.ArrayList;
import java.util.List;

final class LinearScanners {

    private static final boolean[] LOCAL = new boolean[128];
    private static final boolean[] DOMAIN = new boolean[128];

    static final int MAX_LOCAL_SCAN = 256;
    static final int MAX_DOMAIN_SCAN = 512;

    static {
        for (char c = 'a'; c <= 'z'; c++) {
            LOCAL[c] = true;
            DOMAIN[c] = true;
        }
        for (char c = 'A'; c <= 'Z'; c++) {
            LOCAL[c] = true;
            DOMAIN[c] = true;
        }
        for (char c = '0'; c <= '9'; c++) {
            LOCAL[c] = true;
            DOMAIN[c] = true;
        }
        LOCAL['.'] = true;
        LOCAL['_'] = true;
        LOCAL['%'] = true;
        LOCAL['+'] = true;
        LOCAL['-'] = true;
        DOMAIN['.'] = true;
        DOMAIN['-'] = true;
    }

    private LinearScanners() {
    }

    static boolean isLocal(char c) {
        return c < 128 && LOCAL[c];
    }

    static boolean isDomain(char c) {
        return c < 128 && DOMAIN[c];
    }

    static List<int[]> emailHits(String text) {
        List<int[]> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (text.charAt(i) != '@') {
                i++;
                continue;
            }
            int start = i;
            int back = 0;
            while (start > 0 && back < MAX_LOCAL_SCAN && isLocal(text.charAt(start - 1))) {
                start--;
                back++;
            }
            if (start == i) {
                i++;
                continue;
            }
            int j = i + 1;
            int dollarEnd = j;
            while (j < n && j - i <= MAX_DOMAIN_SCAN && isDomain(text.charAt(j))) {
                j++;
                dollarEnd = j;
            }
            int lastDot = -1;
            for (int k = i + 1; k < dollarEnd; k++) {
                if (text.charAt(k) == '.') {
                    lastDot = k;
                }
            }
            int end = -1;
            if (lastDot > i + 1) {
                int tldEnd = lastDot + 1;
                int letters = 0;
                while (tldEnd < dollarEnd && isAsciiLetter(text.charAt(tldEnd))) {
                    tldEnd++;
                    letters++;
                }
                if (letters >= 2) {
                    end = tldEnd;
                }
            }
            if (end > start) {
                out.add(new int[]{start, end});
                i = end;
            } else {
                i++;
            }
        }
        return out;
    }

    static boolean containsEmail(String text) {
        return !emailHits(text).isEmpty();
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
