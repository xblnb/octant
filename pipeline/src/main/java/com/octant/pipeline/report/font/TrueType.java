package com.octant.pipeline.report.font;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class TrueType {

    private final byte[] data;
    private final Map<String, int[]> tables = new LinkedHashMap<>();
    private final int unitsPerEm;
    private final int indexToLocFormat;
    private final int numGlyphs;
    private final int[] loca;
    private final int ascent;
    private final int descent;
    private final int[] advanceWidths;
    private final int[] leftSideBearings;
    private final int[] bbox;
    private final String familyName;
    private final String postScriptName;
    private final String source;
    private final Map<Integer, Integer> unicodeToGlyph;

    private TrueType(byte[] data, String source) throws IOException {
        this.data = data;
        this.source = source;
        readTableDirectory();
        int[] head = requireTable("head");
        this.unitsPerEm = u16(head[0] + 18);
        this.indexToLocFormat = (int) s16(head[0] + 50);
        int[] maxp = requireTable("maxp");
        this.numGlyphs = u16(maxp[0] + 4);
        int[] hhea = requireTable("hhea");
        this.ascent = (int) s16(hhea[0] + 4);
        this.descent = (int) s16(hhea[0] + 6);
        int numberOfHMetrics = u16(hhea[0] + 34);
        this.loca = readLoca(requireTable("loca")[0]);
        int[] hmtx = requireTable("hmtx");
        this.advanceWidths = new int[numGlyphs];
        this.leftSideBearings = new int[numGlyphs];
        int off = hmtx[0];
        int lastAdvance = 0;
        for (int g = 0; g < numGlyphs; g++) {
            if (g < numberOfHMetrics) {
                lastAdvance = u16(off);
                leftSideBearings[g] = (int) s16(off + 2);
                off += 4;
            } else {
                leftSideBearings[g] = (int) s16(off);
                off += 2;
            }
            advanceWidths[g] = lastAdvance;
        }
        this.bbox = new int[]{(int) s16(head[0] + 36), (int) s16(head[0] + 38),
                (int) s16(head[0] + 40), (int) s16(head[0] + 42)};
        this.unicodeToGlyph = readCmap(requireTable("cmap")[0]);
        this.familyName = readNameString(1);
        this.postScriptName = readNameString(6);
    }

    public static TrueType load(Path path) throws IOException {
        return new TrueType(Files.readAllBytes(path), path.toString());
    }

    public static TrueType load(byte[] bytes, String source) throws IOException {
        return new TrueType(bytes, source);
    }

    public int unitsPerEm() {
        return unitsPerEm;
    }

    public int numGlyphs() {
        return numGlyphs;
    }

    public int ascent() {
        return ascent;
    }

    public int descent() {
        return descent;
    }

    public int[] bbox() {
        return bbox.clone();
    }

    public String familyName() {
        return familyName;
    }

    public String postScriptName() {
        return postScriptName;
    }

    public String source() {
        return source;
    }

    public byte[] rawBytes() {
        return data.clone();
    }

    public boolean hasNameTable() {
        return tables.containsKey("name");
    }

    public int tableCount() {
        return tables.size();
    }

    private String readNameString(int nameId) {
        int[] name = tables.get("name");
        if (name == null) {
            return null;
        }
        int count = u16(name[0] + 2);
        int stringOffset = name[0] + u16(name[0] + 4);
        String fallback = null;
        for (int i = 0; i < count; i++) {
            int rec = name[0] + 6 + i * 12;
            int platformId = u16(rec);
            int encodingId = u16(rec + 2);
            int languageId = u16(rec + 4);
            int id = u16(rec + 6);
            int length = u16(rec + 8);
            int offset = u16(rec + 10);
            if (id != nameId || length <= 0) {
                continue;
            }
            String value = (platformId == 3 || (platformId == 0 && encodingId >= 3))
                    ? new String(data, stringOffset + offset, length,
                            java.nio.charset.StandardCharsets.UTF_16BE)
                    : new String(data, stringOffset + offset, length,
                            java.nio.charset.StandardCharsets.ISO_8859_1);
            value = value.replace("\u0000", "").trim();
            if (value.isEmpty()) {
                continue;
            }
            if ((platformId == 3 && encodingId == 1) || platformId == 0) {
                return value;
            }
            if (fallback == null || languageId == 0x0409) {
                fallback = value;
            }
        }
        return fallback;
    }

    public int glyphFor(int codePoint) {
        return unicodeToGlyph.getOrDefault(codePoint, 0);
    }

    public double widthEm(int glyphId) {
        return glyphId >= 0 && glyphId < numGlyphs
                ? advanceWidths[glyphId] / (double) unitsPerEm : 0.0d;
    }

    private void readTableDirectory() throws IOException {
        if (data.length < 12) {
            throw new IOException("字体文件过短");
        }
        int tag = u32(0);
        boolean sfnt = tag == 0x00010000 || tag == 0x74727565;
        if (!sfnt) {
            throw new IOException("不是 TrueType 单面字体（tag=0x" + Integer.toHexString(tag)
                    + "）；.ttc 集合字体与 CFF/OTF 暂不支持");
        }
        int numTables = u16(4);
        for (int i = 0; i < numTables; i++) {
            int rec = 12 + i * 16;
            String name = new String(data, rec, 4, java.nio.charset.StandardCharsets.US_ASCII);
            tables.put(name, new int[]{u32(rec + 8), u32(rec + 12)});
        }
    }

    private int[] requireTable(String name) throws IOException {
        int[] t = tables.get(name);
        if (t == null) {
            throw new IOException("字体缺少必需表：" + name);
        }
        return t;
    }

    private int[] readLoca(int locaOffset) {
        int[] out = new int[numGlyphs + 1];
        if (indexToLocFormat == 0) {
            for (int i = 0; i <= numGlyphs; i++) {
                out[i] = u16(locaOffset + i * 2) * 2;
            }
        } else {
            for (int i = 0; i <= numGlyphs; i++) {
                out[i] = u32(locaOffset + i * 4);
            }
        }
        return out;
    }

    private Map<Integer, Integer> readCmap(int cmapOffset) throws IOException {
        int numSubTables = u16(cmapOffset + 2);
        int best = -1;
        int bestScore = -1;
        for (int i = 0; i < numSubTables; i++) {
            int rec = cmapOffset + 4 + i * 8;
            int platformId = u16(rec);
            int encodingId = u16(rec + 2);
            int subOffset = cmapOffset + u32(rec + 4);
            int format = u16(subOffset);
            int score = -1;
            if (platformId == 3 && encodingId == 10 && format == 12) {
                score = 100;
            } else if (platformId == 0 && format == 12) {
                score = 90;
            } else if (platformId == 3 && encodingId == 1 && format == 4) {
                score = 80;
            } else if (platformId == 0 && format == 4) {
                score = 70;
            }
            if (score > bestScore) {
                bestScore = score;
                best = subOffset;
            }
        }
        if (best < 0) {
            throw new IOException("字体没有可用的 Unicode cmap 子表");
        }
        int format = u16(best);
        return format == 12 ? readCmap12(best) : readCmap4(best);
    }

    private Map<Integer, Integer> readCmap4(int off) {
        Map<Integer, Integer> map = new TreeMap<>();
        int segCountX2 = u16(off + 6);
        int segCount = segCountX2 / 2;
        int endOff = off + 14;
        int startOff = endOff + segCountX2 + 2;
        int deltaOff = startOff + segCountX2;
        int rangeOff = deltaOff + segCountX2;
        for (int s = 0; s < segCount; s++) {
            int end = u16(endOff + s * 2);
            int start = u16(startOff + s * 2);
            int delta = (int) s16(deltaOff + s * 2);
            int rangeOffset = u16(rangeOff + s * 2);
            if (start == 0xFFFF && end == 0xFFFF) {
                continue;
            }
            for (int c = start; c <= end && c <= 0xFFFF; c++) {
                int gid;
                if (rangeOffset == 0) {
                    gid = (c + delta) & 0xFFFF;
                } else {
                    int glyphIndexOff = rangeOff + s * 2 + rangeOffset + (c - start) * 2;
                    if (glyphIndexOff + 1 >= data.length) {
                        continue;
                    }
                    gid = u16(glyphIndexOff);
                    if (gid != 0) {
                        gid = (gid + delta) & 0xFFFF;
                    }
                }
                if (gid != 0) {
                    map.put(c, gid);
                }
            }
        }
        return map;
    }

    private Map<Integer, Integer> readCmap12(int off) {
        Map<Integer, Integer> map = new TreeMap<>();
        int groups = u32(off + 12);
        int rec = off + 16;
        for (int g = 0; g < groups; g++, rec += 12) {
            int start = u32(rec);
            int end = u32(rec + 4);
            int startGid = u32(rec + 8);
            for (int c = start, gid = startGid; c <= end; c++, gid++) {
                map.put(c, gid);
            }
        }
        return map;
    }

    private int u16(int off) {
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }

    private short s16(int off) {
        return (short) u16(off);
    }

    private int u32(int off) {
        return ((data[off] & 0xFF) << 24) | ((data[off + 1] & 0xFF) << 16)
                | ((data[off + 2] & 0xFF) << 8) | (data[off + 3] & 0xFF);
    }

    public FontPalette paletteFor(String allText) {
        Map<Integer, FontPalette.Entry> entries = new TreeMap<>();
        Map<Integer, FontPalette.Entry> missing = new TreeMap<>();
        java.util.LinkedHashSet<Integer> codepoints = new java.util.LinkedHashSet<>();
        for (int i = 0; i < allText.length(); ) {
            int cp = allText.codePointAt(i);
            i += Character.charCount(cp);
            if (FontPalette.isIgnorable(cp)) {
                continue;
            }
            codepoints.add(cp);
        }
        for (int cp : codepoints) {
            int gid = glyphFor(cp);
            FontPalette.Entry e = new FontPalette.Entry(cp, gid, widthEm(gid));
            if (gid == 0) {
                missing.put(cp, e);
            } else {
                entries.put(cp, e);
            }
        }
        return new FontPalette(source, FontPalette.PDF_FAMILY_NAME, entries, missing);
    }

    public FontPalette paletteFor(String allText, String pdfFamilyName) {
        FontPalette base = paletteFor(allText);
        return new FontPalette(base.sourcePath(),
                pdfFamilyName == null || pdfFamilyName.isBlank()
                        ? FontPalette.PDF_FAMILY_NAME : pdfFamilyName,
                new java.util.LinkedHashMap<>(base.entries()),
                new java.util.LinkedHashMap<>(base.missing()));
    }

    public byte[] subset(boolean[] usedGlyphs) throws IOException {
        int glyphCount = numGlyphs;
        boolean[] used = usedGlyphs.clone();
        used[0] = true;

        byte[] glyf = buildGlyf(used);
        byte[] loca = buildLoca(used);
        byte[] hmtx = buildHmtx(used);
        byte[] head = slice(requireTable("head")[0], 54);
        byte[] hhea = slice(requireTable("hhea")[0], 36);
        byte[] maxp = slice(requireTable("maxp")[0], 32);
        byte[] cmap = buildCmap(used);

        List<String> names = new ArrayList<>(List.of("cmap", "glyf", "head", "hhea", "hmtx", "loca", "maxp"));
        Map<String, byte[]> payloads = new LinkedHashMap<>();
        payloads.put("cmap", cmap);
        payloads.put("glyf", glyf);
        payloads.put("head", head);
        payloads.put("hhea", hhea);
        payloads.put("hmtx", hmtx);
        payloads.put("loca", loca);
        payloads.put("maxp", maxp);

        ByteBuffer hb = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN);
        hb.putShort(50, (short) 1);
        hb.putInt(8, 0);

        int n = names.size();
        int searchRange = Integer.highestOneBit(n) * 16;
        int entrySelector = Integer.numberOfTrailingZeros(Integer.highestOneBit(n));
        int rangeShift = n * 16 - searchRange;

        ByteBuffer out = ByteBuffer.allocate(estimateSize(payloads)).order(ByteOrder.BIG_ENDIAN);
        out.putInt(0x00010000);
        out.putShort((short) n);
        out.putShort((short) searchRange);
        out.putShort((short) entrySelector);
        out.putShort((short) rangeShift);

        int dirStart = out.position();
        int dataStart = dirStart + n * 16;
        int cursor = dataStart;
        List<Integer> offsets = new ArrayList<>();
        for (String name : names) {
            byte[] payload = payloads.get(name);
            cursor = (cursor + 3) & ~3;
            offsets.add(cursor);
            cursor += payload.length;
        }
        for (int i = 0; i < n; i++) {
            String name = names.get(i);
            byte[] payload = payloads.get(name);
            out.position(dirStart + i * 16);
            out.put(name.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.putInt(tableChecksum(payload));
            out.putInt(offsets.get(i));
            out.putInt(payload.length);
        }
        for (int i = 0; i < n; i++) {
            out.position(offsets.get(i));
            out.put(payloads.get(names.get(i)));
        }
        byte[] result = new byte[out.position()];
        System.arraycopy(out.array(), 0, result, 0, result.length);
        return result;
    }

    private int estimateSize(Map<String, byte[]> payloads) {
        int total = 12 + payloads.size() * 16 + 64;
        for (byte[] p : payloads.values()) {
            total += p.length + 4;
        }
        return total;
    }

    private byte[] slice(int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(data, off, out, 0, Math.min(len, data.length - off));
        return out;
    }

    private byte[] buildGlyf(boolean[] used) {
        int[] starts = new int[numGlyphs + 1];
        int size = 0;
        for (int g = 0; g < numGlyphs; g++) {
            starts[g] = size;
            if (used[g]) {
                size += Math.max(0, loca[g + 1] - loca[g]);
            }
        }
        starts[numGlyphs] = size;
        byte[] out = new byte[size + 3];
        for (int g = 0; g < numGlyphs; g++) {
            if (!used[g]) {
                continue;
            }
            int len = loca[g + 1] - loca[g];
            if (len > 0) {
                String glyfName = "glyf";
                int[] t = tables.get(glyfName);
                System.arraycopy(data, t[0] + loca[g], out, starts[g], len);
            }
        }
        return out;
    }

    private byte[] buildLoca(boolean[] used) {
        int[] starts = new int[numGlyphs + 1];
        int size = 0;
        for (int g = 0; g < numGlyphs; g++) {
            starts[g] = size;
            if (used[g]) {
                size += Math.max(0, loca[g + 1] - loca[g]);
            }
        }
        starts[numGlyphs] = size;
        ByteBuffer buf = ByteBuffer.allocate((numGlyphs + 1) * 4).order(ByteOrder.BIG_ENDIAN);
        for (int g = 0; g <= numGlyphs; g++) {
            buf.putInt(starts[g]);
        }
        return buf.array();
    }

    private byte[] buildHmtx(boolean[] used) throws IOException {
        int numberOfHMetrics = u16(requireTable("hhea")[0] + 34);
        int total = numberOfHMetrics * 4 + (numGlyphs - numberOfHMetrics) * 2;
        byte[] out = new byte[total];
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        for (int g = 0; g < numberOfHMetrics && g < numGlyphs; g++) {
            buf.putShort(g * 4, (short) (used[g] ? advanceWidths[g] : 0));
            buf.putShort(g * 4 + 2, (short) (used[g] ? leftSideBearings[g] : 0));
        }
        int off = numberOfHMetrics * 4;
        for (int g = numberOfHMetrics; g < numGlyphs; g++, off += 2) {
            buf.putShort(off, (short) (used[g] ? leftSideBearings[g] : 0));
        }
        return out;
    }

    private byte[] buildCmap(boolean[] used) {
        List<Integer> cps = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : unicodeToGlyph.entrySet()) {
            int cp = e.getKey();
            int gid = e.getValue();
            if (cp <= 0xFFFF && gid < numGlyphs && used[gid]) {
                cps.add(cp);
            }
        }
        List<int[]> segments = new ArrayList<>();
        int i = 0;
        while (i < cps.size()) {
            int start = cps.get(i);
            int end = start;
            int firstGid = unicodeToGlyph.get(start);
            while (i + 1 < cps.size()) {
                int next = cps.get(i + 1);
                int nextGid = unicodeToGlyph.get(next);
                if (next == end + 1 && nextGid == unicodeToGlyph.get(end) + 1) {
                    end = next;
                    i++;
                } else {
                    break;
                }
            }
            segments.add(new int[]{start, end, firstGid});
            i++;
        }
        int segCount = segments.size() + 1;
        int segCountX2 = segCount * 2;
        int searchRange = Integer.highestOneBit(segCount) * 2;
        int entrySelector = Integer.numberOfTrailingZeros(Integer.highestOneBit(segCount));
        int rangeShift = segCountX2 - searchRange;

        int subtableLength = 16 + segCountX2 * 4 + 2;
        int tableHeaderLength = 4 + 8;
        int length = tableHeaderLength + subtableLength;
        ByteBuffer buf = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) 0);
        buf.putShort((short) 1);
        buf.putShort((short) 3);
        buf.putShort((short) 1);
        buf.putInt(tableHeaderLength);
        buf.putShort((short) 4);
        buf.putShort((short) subtableLength);
        buf.putShort((short) 0);
        buf.putShort((short) segCountX2);
        buf.putShort((short) searchRange);
        buf.putShort((short) entrySelector);
        buf.putShort((short) rangeShift);
        for (int[] seg : segments) {
            buf.putShort((short) seg[1]);
        }
        buf.putShort((short) 0xFFFF);
        buf.putShort((short) 0);
        for (int[] seg : segments) {
            buf.putShort((short) seg[0]);
        }
        buf.putShort((short) 0xFFFF);
        for (int[] seg : segments) {
            int delta = (seg[2] - seg[0]) & 0xFFFF;
            buf.putShort((short) delta);
        }
        buf.putShort((short) 1);
        for (int i2 = 0; i2 < segments.size(); i2++) {
            buf.putShort((short) 0);
        }
        buf.putShort((short) 0);
        return buf.array();
    }

    private int tableChecksum(byte[] table) {
        int sum = 0;
        int i = 0;
        for (; i + 4 <= table.length; i += 4) {
            sum += ((table[i] & 0xFF) << 24) | ((table[i + 1] & 0xFF) << 16)
                    | ((table[i + 2] & 0xFF) << 8) | (table[i + 3] & 0xFF);
        }
        int rem = table.length - i;
        if (rem > 0) {
            int v = 0;
            for (int k = 0; k < 4; k++) {
                v = (v << 8) | (k < rem ? (table[i + k] & 0xFF) : 0);
            }
            sum += v;
        }
        return sum;
    }

    public boolean has(int codePoint) {
        return unicodeToGlyph.containsKey(codePoint);
    }

    public List<String> tableNames() {
        return List.copyOf(tables.keySet());
    }

    public static Optional<Path> bestFontFor(String text, List<Path> candidates) {
        Path best = null;
        int bestScore = -1;
        for (Path p : candidates) {
            if (!Files.isReadable(p)) {
                continue;
            }
            try {
                TrueType t = load(p);
                int score = 0;
                for (int i = 0; i < text.length(); ) {
                    int cp = text.codePointAt(i);
                    i += Character.charCount(cp);
                    if (FontPalette.isIgnorable(cp)) {
                        continue;
                    }
                    if (t.has(cp)) {
                        score++;
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            } catch (IOException e) {
            }
        }
        return Optional.ofNullable(best);
    }
}
