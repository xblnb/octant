package com.octant.pipeline;

import com.octant.pipeline.report.font.FontPalette;
import com.octant.pipeline.report.font.ReportFontResolver;
import com.octant.pipeline.report.font.TrueType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratorVocabularyCoverageTest {

    private static final String EXTRA_PUNCTUATION = "—–…、。（）：；，？";

    @Test
    @DisplayName("jar 内 OFL 字体必须覆盖生成器全部词汇（pipeline/src + common/src）")
    void bundledFontCoversGeneratorVocabulary() throws IOException {
        Set<Integer> vocabulary = generatorVocabulary();
        Optional<TrueType> bundled = loadBundledFont();
        assertTrue(bundled.isPresent(),
                "找不到 jar 内字体资产：" + ReportFontResolver.BUNDLED_FONT_RESOURCE
                        + "（仓库内的 OFL 子集）");
        TrueType font = bundled.get();

        List<Integer> missing = vocabulary.stream().filter(cp -> !font.has(cp)).sorted().toList();
        StringBuilder detail = new StringBuilder();
        for (int cp : missing.subList(0, Math.min(30, missing.size()))) {
            detail.append(String.format("U+%04X('%s') ", cp, new String(Character.toChars(cp))));
        }
        System.out.println("== 生成器词汇覆盖 ==");
        System.out.println("distinct 非 ASCII 字符 = " + vocabulary.size());
        System.out.println("字体字形数 = " + font.numGlyphs());
        System.out.println("缺失 = " + missing.size() + (missing.isEmpty() ? "" : "：" + detail));

        assertFalse(vocabulary.isEmpty(), "词汇集为空说明源码扫描失败（断言会变成空转）");
        assertTrue(missing.isEmpty(),
                "字体缺生成器词汇（换存档/改文案就会缺字）：" + detail);
    }

    @Test
    @DisplayName("报告文案 + 门禁补充标点必须被调色板完整覆盖（缺一即失败）")
    void paletteCoversReportAndPunctuation() {
        String text = "有效进度 游玩时长 游玩广度 进度离散度 内容偏好分布 沉冗复杂行为 生物战斗分析"
                + " 玩家画像 独家内容" + EXTRA_PUNCTUATION;
        Optional<ReportFontResolver.Resolved> resolved = ReportFontResolver.resolve(text);
        assertTrue(resolved.isPresent(), "找不到可用字体");
        FontPalette palette = resolved.get().palette();
        List<Integer> missing = palette.missingCodePoints(text);
        assertTrue(missing.isEmpty(),
                "调色板缺字：" + missing.stream().map(cp -> String.format("U+%04X", cp)).toList());
        System.out.println("报告词汇覆盖：字形 " + palette.glyphCount()
                + "，缺字 " + missing.size() + "，字体来源 " + resolved.get().fontFile());
    }

    private static Set<Integer> generatorVocabulary() throws IOException {
        Path root = repoRoot();
        List<Path> roots = List.of(root.resolve("pipeline/src/main"), root.resolve("common/src/main"));
        Set<Integer> out = new TreeSet<>();
        for (Path r : roots) {
            if (!Files.isDirectory(r)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(r)) {
                for (Path p : walk.filter(x -> x.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    for (String literal : stringLiterals(text)) {
                        literal.codePoints().filter(cp -> cp > 0x7F)
                                .filter(cp -> !FontPalette.isIgnorable(cp))
                                .forEach(out::add);
                    }
                }
            }
        }
        for (int cp : EXTRA_PUNCTUATION.codePoints().toArray()) {
            out.add(cp);
        }
        return out;
    }

    private static List<String> stringLiterals(String src) {
        List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = null;
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (cur != null) {
                if (c == '\\' && i + 1 < n) {
                    cur.append(src.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    out.add(cur.toString());
                    cur = null;
                    i++;
                    continue;
                }
                cur.append(c);
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n && src.charAt(i) != '\'') {
                    if (src.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i++;
                continue;
            }
            if (c == '"') {
                cur = new StringBuilder();
                i++;
                continue;
            }
            i++;
        }
        return out;
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("docs/privacy/SPEC-VERSION.txt"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return Path.of("").toAbsolutePath();
    }

    private static Optional<TrueType> loadBundledFont() {
        try (java.io.InputStream in = GeneratorVocabularyCoverageTest.class
                .getResourceAsStream(ReportFontResolver.BUNDLED_FONT_RESOURCE)) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional.of(TrueType.load(in.readAllBytes(), "bundled"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Set<Integer> asSet(int... codepoints) {
        Set<Integer> s = new LinkedHashSet<>();
        for (int cp : codepoints) {
            s.add(cp);
        }
        return s;
    }
}
