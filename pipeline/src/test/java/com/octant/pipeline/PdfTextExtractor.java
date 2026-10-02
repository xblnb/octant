package com.octant.pipeline;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

public final class PdfTextExtractor {

    private static final Pattern TJ_HEX = Pattern.compile("<([0-9A-Fa-f]+)>\\s*Tj");
    private static final Pattern PAGE_OBJ = Pattern.compile("/Type\\s*/Page(?![s])");
    private static final Pattern BFCHAR = Pattern.compile("beginbfchar(.*?)endbfchar", Pattern.DOTALL);
    private static final Pattern BFRANGE = Pattern.compile("beginbfrange(.*?)endbfrange", Pattern.DOTALL);
    private static final Pattern PAIR = Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>");
    private static final Pattern BFRANGE_TRIPLE =
            Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>");
    private static final Pattern STREAM = Pattern.compile("stream\r?\n(.*?)\r?\nendstream", Pattern.DOTALL);

    private final byte[] pdf;
    private final String latin;
    private final Map<Integer, String> toUnicode;
    private final List<Integer> unmappedCodes = new ArrayList<>();

    public PdfTextExtractor(byte[] pdf) {
        this.pdf = pdf;
        this.latin = new String(pdf, StandardCharsets.ISO_8859_1);
        this.toUnicode = parseToUnicode();
    }

    public boolean hasValidHeader() {
        return latin.startsWith("%PDF-1.4");
    }

    public boolean hasValidTrailer() {
        return latin.contains("trailer") && latin.contains("startxref") && latin.contains("%%EOF");
    }

    public int pageCount() {
        Matcher m = PAGE_OBJ.matcher(latin);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    public boolean hasEmbeddedFontFile() {
        return latin.contains("/FontFile2");
    }

    public boolean hasCidToGidMap() {
        return latin.contains("/CIDToGIDMap");
    }

    public String cidFontSubtype() {
        if (latin.contains("/CIDFontType2")) {
            return "CIDFontType2";
        }
        if (latin.contains("/CIDFontType0")) {
            return "CIDFontType0";
        }
        return "UNKNOWN";
    }

    public int embeddedFontBytes() {
        Matcher m = Pattern.compile("/Length1\\s+(\\d+)").matcher(latin);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    public int toUnicodeEntryCount() {
        return toUnicode.size();
    }

    public List<Integer> unmappedCodes() {
        return List.copyOf(unmappedCodes);
    }

    public String text() {
        unmappedCodes.clear();
        StringBuilder sb = new StringBuilder();
        Matcher m = TJ_HEX.matcher(decompressedContent(latin));
        while (m.find()) {
            String hex = m.group(1);
            for (int i = 0; i + 4 <= hex.length(); i += 4) {
                int code = Integer.parseInt(hex.substring(i, i + 4), 16);
                String mapped = toUnicode.get(code);
                if (mapped == null) {
                    unmappedCodes.add(code);
                    sb.append('\uFFFD');
                } else {
                    sb.append(mapped);
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String decompressedContent(String raw) {
        StringBuilder all = new StringBuilder(raw);
        Matcher streams = STREAM.matcher(raw);
        while (streams.find()) {
            byte[] bytes = streams.group(1).getBytes(StandardCharsets.ISO_8859_1);
            String inflated = tryInflate(bytes);
            if (inflated != null) {
                all.append('\n').append(inflated);
            }
        }
        return all.toString();
    }

    public List<String> lines() {
        List<String> out = new ArrayList<>();
        for (String line : text().split("\n")) {
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    public boolean contains(String needle) {
        return text().contains(needle);
    }

    public byte[] bytes() {
        return pdf;
    }

    private Map<Integer, String> parseToUnicode() {
        Map<Integer, String> map = new TreeMap<>();
        for (String cmapText : candidateCmapTexts()) {
            Matcher bf = BFCHAR.matcher(cmapText);
            while (bf.find()) {
                Matcher pair = PAIR.matcher(bf.group(1));
                while (pair.find()) {
                    map.put(Integer.parseInt(pair.group(1), 16), hexToUnicode(pair.group(2)));
                }
            }
            Matcher br = BFRANGE.matcher(cmapText);
            while (br.find()) {
                Matcher triple = BFRANGE_TRIPLE.matcher(br.group(1));
                while (triple.find()) {
                    int lo = Integer.parseInt(triple.group(1), 16);
                    int hi = Integer.parseInt(triple.group(2), 16);
                    int dst = Integer.parseInt(triple.group(3), 16);
                    for (int c = lo; c <= hi; c++) {
                        map.put(c, new String(Character.toChars(dst + (c - lo))));
                    }
                }
            }
        }
        return map;
    }

    private List<String> candidateCmapTexts() {
        List<String> out = new ArrayList<>();
        out.add(latin);
        Matcher streams = STREAM.matcher(latin);
        while (streams.find()) {
            byte[] raw = java.util.Arrays.copyOfRange(pdf, streams.start(1), streams.end(1));
            String inflated = tryInflate(raw);
            if (inflated != null && inflated.contains("begincmap")) {
                out.add(inflated);
            }
        }
        return out;
    }

    private String tryInflate(byte[] data) {
        try {
            Inflater inflater = new Inflater();
            inflater.setInput(data);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(64, data.length * 4));
            byte[] buf = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    break;
                }
                buffer.write(buf, 0, n);
            }
            inflater.end();
            return new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);
        } catch (Exception e) {
            return null;
        }
    }

    private static String hexToUnicode(String hex) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 4 <= hex.length(); i += 4) {
            sb.append((char) Integer.parseInt(hex.substring(i, i + 4), 16));
        }
        return sb.toString();
    }

    public Map<Integer, String> toUnicodeSample(int limit) {
        Map<Integer, String> out = new LinkedHashMap<>();
        int n = 0;
        for (Map.Entry<Integer, String> e : toUnicode.entrySet()) {
            if (n++ >= limit) {
                break;
            }
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }
}
