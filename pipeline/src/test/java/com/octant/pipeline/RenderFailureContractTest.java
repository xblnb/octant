package com.octant.pipeline;

import com.octant.pipeline.report.PdfReport;
import com.octant.pipeline.report.PdfWriter;
import com.octant.pipeline.report.RenderFailure;
import com.octant.pipeline.report.font.FontPalette;
import com.octant.pipeline.report.font.ReportFontResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderFailureContractTest {

    private static final String ABSENT = "\u3402";
    private static final String NON_BMP = "\uD834\uDD1E";

    private static ReportFontResolver.Resolved resolve(String text) {
        Optional<ReportFontResolver.Resolved> r = ReportFontResolver.resolve(text);
        assertTrue(r.isPresent(), "本机应能解析到随包 OFL 字体资产（否则后续断言无意义）");
        return r.get();
    }

    @Test
    @DisplayName("契约来源：默认解析命中的是**随包 OFL 资产**，不是系统字体")
    void defaultResolutionUsesBundledAsset() {
        ReportFontResolver.Resolved r = resolve("存档内行为洞察报告 0-9 %");
        System.out.println("font=" + r.fontFile() + " provenance=" + r.provenance()
                + " family=" + r.familyName() + " psName=" + r.postScriptName()
                + " embedded=" + r.subsetBytes() + "B glyphs=" + r.glyphCount()
                + " tables=" + r.tableCount() + " sha256=" + r.fontSha256());
        assertEquals(ReportFontResolver.PROVENANCE_BUNDLED, r.provenance(),
                "默认解析必须是随包 OFL 资产：命中系统字体意味着导出物的许可状态随机器而变");
        assertFalse(r.fontFile().toLowerCase().contains("simhei"),
                "不得再默认嵌入厂商系统字体（不可随包分发）");
        assertEquals(ReportFontResolver.bundledAssetSha256().orElseThrow(),
                r.fontSha256(),
                "产物里要嵌入的字体程序必须**逐字节等于**随包资产（防漂移的核心断言）");
        assertEquals(r.fontSha256(), ReportFontResolver.sha256(r.subset()));
    }

    @Test
    @DisplayName("系统字体候选已按裁决清空；非 .ttf 的显式覆盖被拒绝而不是静默使用")
    void systemFontCandidatesAreRemoved() {
        assertTrue(ReportFontResolver.DEFAULT_CANDIDATES.isEmpty(),
                "『.ttc 回退：不支持，明确删掉』『默认必须解析到 classpath 资产』"
                        + " ⇒ 系统候选必须为空，否则导出物的许可状态随机器漂移");
        System.setProperty(ReportFontResolver.FONT_PROPERTY, "C:/Windows/Fonts/simhei.ttc");
        try {
            ReportFontResolver.Resolved r = resolve("存档");
            assertEquals(ReportFontResolver.PROVENANCE_BUNDLED, r.provenance(),
                    "非 .ttf 的覆盖项应被忽略并回落到随包资产，而不是被当成可用字体");
        } finally {
            System.clearProperty(ReportFontResolver.FONT_PROPERTY);
        }
    }

    @Test
    @DisplayName("嵌入字体结构完整：有 name 表、upem=1000、与资产同表数（不再是自拼的 7 张表）")
    void embeddedFontIsStructurallyComplete() {
        ReportFontResolver.Resolved r = resolve("存档");
        assertTrue(r.font().hasNameTable(),
                "OpenType 要求 name 表存在；缺它的字体严格解析器（fontTools）直接失败");
        assertEquals(1000, r.font().unitsPerEm(),
                "资产 unitsPerEm=1000（simhei 是 256 —— 这条能直接区分两者）");
        assertTrue(r.tableCount() >= 16,
                "嵌原字节后表数应与资产一致（实测 17 张）；旧的自拼子集只有 7 张表。实测 = "
                        + r.tableCount());
        assertTrue(r.postScriptName() != null && !r.postScriptName().isBlank(),
                "字体名必须从 name 表读出（nameID 6），不能自拟");
    }

    @Test
    @DisplayName("字形子集路径仍产出结构合法的 sfnt（保留但已非默认路径，防其腐化）")
    void subsetPathStillProducesValidSfnt() throws Exception {
        ReportFontResolver.Resolved r = resolve("存档");
        boolean[] used = new boolean[r.font().numGlyphs()];
        for (var e : r.palette().entries().values()) {
            if (e.glyphId() > 0 && e.glyphId() < used.length) {
                used[e.glyphId()] = true;
            }
        }
        byte[] subset = r.font().subset(used);
        assertTrue(subset.length > 1000, "子集不应为空");
        assertEquals(0, subset[0]);
        assertEquals(1, subset[1]);
        int numTables = u16(subset, 4);
        for (int i = 0; i < numTables; i++) {
            int rec = 12 + 16 * i;
            String tag = new String(subset, rec, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (!"cmap".equals(tag)) {
                continue;
            }
            int off = u32(subset, rec + 8);
            assertEquals(0, u16(subset, off), "cmap 表头必须 version=0");
            assertEquals(1, u16(subset, off + 2), "cmap 表头必须 numTables=1");
            assertEquals(3, u16(subset, off + 4), "子表 platform 应为 3");
            assertEquals(1, u16(subset, off + 6), "子表 encoding 应为 1");
            int subOff = u32(subset, off + 8);
            assertEquals(4, u16(subset, off + subOff), "子表 format 应为 4");
            System.out.println("subset sfnt: tables=" + numTables + " cmap version="
                    + u16(subset, off) + " numTables=" + u16(subset, off + 2)
                    + " platform=" + u16(subset, off + 4) + " encoding=" + u16(subset, off + 6)
                    + " subtableFormat=" + u16(subset, off + subOff));
            return;
        }
        throw new AssertionError("子集里没有 cmap 表");
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int u32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    @Test
    @DisplayName("PDF 声明的字体名 == 嵌入字体的 PostScript 名（前缀由 writer 加）")
    void pdfDeclaresTheEmbeddedFontName() {
        ReportFontResolver.Resolved r = resolve("存档");
        assertEquals(r.postScriptName(), r.palette().familyName(),
                "产物声明的字体名必须来自字体自身，自拟名字会让阅读器的字体匹配不可预期");
        System.out.println("declared font (PDF /BaseFont) = MCINSA+" + r.palette().familyName());
    }

    @Test
    @DisplayName("gid == 0 的字符进入调色板时落在 missing，而不是 entries")
    void absentGlyphGoesToMissing() {
        ReportFontResolver.Resolved r = resolve("存档" + ABSENT);
        assertTrue(r.palette().entry(ABSENT.codePointAt(0)).isEmpty(),
                "字体没有该字形的字符不得出现在调色板里（否则发射时会印成空白）");
        assertEquals(List.of(ABSENT.codePointAt(0)),
                r.palette().missingCodePoints("存档" + ABSENT),
                "缺字必须能被逐码点枚举出来");
    }

    @Test
    @DisplayName("写入器遇到缺字必须显式抛出 RenderFailure，并带闭集内的 reasonCode")
    void writerFailsExplicitlyOnMissingGlyph() {
        ReportFontResolver.Resolved r = resolve("存档");
        PdfWriter pdf = new PdfWriter(r.palette(), r.subset());
        pdf.line("存档" + ABSENT);
        RenderFailure ex = assertThrows(RenderFailure.class, pdf::toPdf,
                "缺字必须抛异常：静默渲染会让缺字在产物层完全不可见");
        assertEquals(RenderFailure.SOURCE_UNAVAILABLE, ex.reasonCode());
        assertTrue(ex.codePoints().contains(ABSENT.codePointAt(0)),
                "异常必须点名是哪个码点缺字形，否则排障只能靠猜");
        System.out.println("reasonCode=" + ex.reasonCode() + " cps="
                + RenderFailure.describe(ex.codePoints(), 8));
    }

    @Test
    @DisplayName("增补平面字符同样显式失败（不得静默替换成 `?`）")
    void writerFailsExplicitlyOnNonBmp() {
        ReportFontResolver.Resolved r = resolve("存档");
        PdfWriter pdf = new PdfWriter(r.palette(), r.subset());
        pdf.line("存档" + NON_BMP);
        RenderFailure ex = assertThrows(RenderFailure.class, pdf::toPdf,
                "旧实现把增补平面字符替换成 `?` —— 那是静默改写报告内容");
        assertEquals(RenderFailure.SOURCE_UNAVAILABLE, ex.reasonCode());
        assertTrue(ex.codePoints().contains(NON_BMP.codePointAt(0)));
    }

    @Test
    @DisplayName("全字符可用时正常出字节（守卫不能把正常路径也拒掉）")
    void writerStillWorksForCoveredText() {
        ReportFontResolver.Resolved r = resolve("存档内行为洞察报告 0-9 %（单位：%）");
        PdfWriter pdf = new PdfWriter(r.palette(), r.subset());
        pdf.line("存档内行为洞察报告");
        byte[] out = pdf.toPdf();
        assertTrue(out.length > 1000, "正常路径必须仍然产出 PDF 字节");
        assertTrue(new String(out, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)
                .startsWith("%PDF-"));
    }

    @Test
    @DisplayName("U+2212 归一到 ASCII `-`（字体资产 README §5.4），且归一化后不再缺字")
    void minusSignIsNormalised() {
        String withMinus = "score = 0.4 \u2212 0.1";
        assertEquals("score = 0.4 - 0.1", PdfReport.typographic(withMinus));
        ReportFontResolver.Resolved r = resolve(withMinus);
        assertEquals(List.of(), r.palette().missingCodePoints(withMinus),
                "归一化后的 U+2212 不应再算作缺字（源字体本就没有该字形）");
    }

    @Test
    @DisplayName("默认可忽略码点集合与资产侧清单一致（控制符 / 零宽 / 变体选择符 / 标签块）")
    void ignorableSetMatchesAssetContract() {
        Set<Integer> mustBeIgnorable = Set.of(
                0x00, 0x1F, 0x7F, 0x9F,
                0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF,
                0xFE00, 0xFE0F,
                0xE0100, 0xE01EF,
                0xE0000, 0xE007F);
        for (int cp : mustBeIgnorable) {
            assertTrue(FontPalette.isIgnorable(cp),
                    String.format("U+%04X 必须被排除出字形覆盖断言，否则会在正确字体上报假缺陷", cp));
        }
        for (int cp : List.of(0x20, 0x4E2D, 0x6587, 0x2014, 0x3002, 0x2212)) {
            assertFalse(FontPalette.isIgnorable(cp),
                    String.format("U+%04X 是真实字符，不得被忽略", cp));
        }
    }

    @Test
    @DisplayName("失败类型必然携带 reasonCode（契约字段不可为空）")
    void failureAlwaysCarriesReasonCode() {
        assertEquals(RenderFailure.SOURCE_UNAVAILABLE,
                new PdfReport.FontUnavailableException("no font").reasonCode());
        assertEquals(RenderFailure.SOURCE_UNAVAILABLE,
                new RenderFailure(RenderFailure.SOURCE_UNAVAILABLE, "gid=0", List.of(0x3402))
                        .reasonCode());
    }
}
