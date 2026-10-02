package com.octant.pipeline.report.font;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ReportFontResolver {

    public static final String BUNDLED_FONT_RESOURCE =
            "/com/octant/pipeline/report/font/NotoSansSC-Report-wide.ttf";

    public static final String BUNDLED_ASSET_FAMILY = "Octant Report Sans";

    public static final String PROVENANCE_BUNDLED = "bundled-ofl-asset";
    public static final String PROVENANCE_EXPLICIT = "explicit-property";
    public static final String PROVENANCE_SYSTEM = "system-font";

    public static final String FONT_PROPERTY = "octant.report.font";

    public static final List<String> DEFAULT_CANDIDATES = List.of();

    public record Resolved(TrueType font, FontPalette palette, byte[] subset,
                           String fontFile, String provenance, String familyName,
                           int subsetBytes, int glyphCount, String fontSha256,
                           String postScriptName, int tableCount) {
    }

    private ReportFontResolver() {
    }

    public static Optional<Resolved> resolve(String allText) {
        String explicit = System.getProperty(FONT_PROPERTY, "");
        if (!explicit.isEmpty()) {
            Path p = Path.of(explicit);
            if (!explicit.toLowerCase(java.util.Locale.ROOT).endsWith(".ttf")) {
                warnOnce("octant.report.font 只接受单面 TrueType（.ttf）：" + explicit
                        + " 被忽略（.ttc/.otc 不受支持）");
            } else {
                Optional<Resolved> r = fromFile(p, allText, PROVENANCE_EXPLICIT, null);
                if (r.isPresent()) {
                    warnOnce("[Octant（卦限）] 使用 -D" + FONT_PROPERTY + "=" + explicit
                            + " 指定的字体：该字体可能不可随包分发，许可责任在调用方；"
                            + "默认来源是随包 OFL 资产（" + BUNDLED_FONT_RESOURCE + "）。");
                    return r;
                }
            }
        }
        return fromBundledResource(allText);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean warned =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void warnOnce(String message) {
        if (warned.compareAndSet(false, true)) {
            System.err.println(message);
        }
    }

    public static boolean warnedAboutNonBundledFont() {
        return warned.get();
    }

    public static Optional<Resolved> fromBundledResource(String allText) {
        try (java.io.InputStream in = ReportFontResolver.class
                .getResourceAsStream(BUNDLED_FONT_RESOURCE)) {
            if (in == null) {
                return Optional.empty();
            }
            byte[] bytes = in.readAllBytes();
            TrueType font = TrueType.load(bytes, "bundled:" + BUNDLED_FONT_RESOURCE);
            return Optional.of(build(font, allText, "jar:" + BUNDLED_FONT_RESOURCE,
                    PROVENANCE_BUNDLED, BUNDLED_ASSET_FAMILY));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Resolved> fromFile(Path path, String allText, String provenance,
                                               String familyName) {
        try {
            TrueType font = TrueType.load(path);
            return Optional.of(build(font, allText, path.toString(), provenance, familyName));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Resolved build(TrueType font, String allText, String source, String provenance,
                                  String familyName) throws IOException {
        String psName = font.postScriptName();
        String pdfName = psName != null && !psName.isBlank()
                ? psName : (font.familyName() != null && !font.familyName().isBlank()
                        ? font.familyName() : familyName);
        FontPalette palette = font.paletteFor(allText, pdfName);
        byte[] embedded = font.rawBytes();
        return new Resolved(font, palette, embedded, source, provenance,
                psName != null && !psName.isBlank() ? font.familyName() : familyName,
                embedded.length, palette.glyphCount(), sha256(embedded),
                psName, font.tableCount());
    }

    public static String sha256(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 必须支持 SHA-256", e);
        }
    }

    public static Optional<String> bundledAssetSha256() {
        try (java.io.InputStream in = ReportFontResolver.class
                .getResourceAsStream(BUNDLED_FONT_RESOURCE)) {
            return in == null ? Optional.empty() : Optional.of(sha256(in.readAllBytes()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static Optional<Path> firstReadableCandidate() {
        for (String c : DEFAULT_CANDIDATES) {
            Path p = Path.of(c);
            if (Files.isReadable(p)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
