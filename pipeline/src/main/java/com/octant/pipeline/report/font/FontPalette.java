package com.octant.pipeline.report.font;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class FontPalette {

    public static final String PDF_FAMILY_NAME = "OctantReport";

    public record Entry(int codePoint, int glyphId, double widthEm) {
    }

    private final String sourcePath;
    private final String familyName;
    private final Map<Integer, Entry> entries;
    private final Map<Integer, Entry> missing;

    FontPalette(String sourcePath, String familyName, Map<Integer, Entry> entries,
                Map<Integer, Entry> missing) {
        this.sourcePath = sourcePath;
        this.familyName = familyName;
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        this.missing = Collections.unmodifiableMap(new LinkedHashMap<>(missing));
    }

    public String sourcePath() {
        return sourcePath;
    }

    public String familyName() {
        return familyName;
    }

    public Map<Integer, Entry> entries() {
        return entries;
    }

    public Map<Integer, Entry> missing() {
        return missing;
    }

    public Optional<Entry> entry(int codePoint) {
        return Optional.ofNullable(entries.get(codePoint));
    }

    public int glyphCount() {
        return entries.size();
    }

    public boolean covers(String text) {
        if (text == null) {
            return true;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!entries.containsKey(cp) && !isIgnorable(cp)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isIgnorable(int cp) {
        return cp == '\n' || cp == '\r' || cp == '\t'
                || cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x2060 || cp == 0xFEFF
                || (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0xE0100 && cp <= 0xE01EF)
                || (cp >= 0xE0000 && cp <= 0xE007F)
                || (cp >= 0x00 && cp <= 0x1F)
                || (cp >= 0x7F && cp <= 0x9F)
                || cp == 0x00AD;
    }

    public java.util.List<Integer> missingCodePoints(String text) {
        java.util.LinkedHashSet<Integer> out = new java.util.LinkedHashSet<>();
        if (text == null) {
            return java.util.List.of();
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!entries.containsKey(cp) && !isIgnorable(cp)) {
                out.add(cp);
            }
        }
        return java.util.List.copyOf(out);
    }
}
