package com.octant.pipeline;

import com.octant.pipeline.analysis.ChartForms;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChartFormAssignmentTest {

    private static final Pattern METRIC_KEY =
            Pattern.compile("`(M\\d(?:-tail|[a-z])|D\\d_[A-Z]+)`");
    private static final Pattern FORM = Pattern.compile("`(C\\d{1,2})`");
    private static final Pattern PROFILE_RANGE = Pattern.compile("`D1…D7`");
    private static final List<String> PROFILE_KEYS = List.of(
            "D1_PACE", "D2_DEPTH", "D3_BREADTH", "D4_DISPERSION", "D5_CRAFT", "D6_COMBAT",
            "D7_PERSIST");

    private static Path repoRoot() {
        String spec = System.getProperty("octant.privacy.specVersionFile", "");
        if (!spec.isEmpty()) {
            Path p = Path.of(spec).getParent();
            if (p != null && p.getParent() != null && p.getParent().getParent() != null) {
                return p.getParent().getParent();
            }
        }
        String e2e = System.getProperty("octant.e2e.out", "");
        if (!e2e.isEmpty()) {
            Path p = Path.of(e2e).getParent();
            if (p != null && p.getParent() != null && p.getParent().getParent() != null) {
                return p.getParent().getParent();
            }
        }
        return Path.of("..").toAbsolutePath().normalize();
    }

    private static Path mappingDoc() {
        Path doc = repoRoot().resolve("docs/design/metric-chart-mapping.md");
        assertTrue(Files.isReadable(doc),
                "找不到图型映射文档：" + doc + " —— 本测试必须解析规范原文，不能用内置常量替身");
        return doc;
    }

    private static Map<String, Set<String>> assignedForms() throws IOException {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(mappingDoc(), StandardCharsets.UTF_8);
        for (String line : lines) {
            if (!line.startsWith("|")) {
                continue;
            }
            String[] cells = line.replaceAll("^\\|", "").replaceAll("\\|\\s*$", "").split("\\|");
            if (cells.length < 3) {
                continue;
            }
            Matcher form = FORM.matcher(cells[2]);
            if (!form.find()) {
                continue;
            }
            String formId = form.group(1);
            if (PROFILE_RANGE.matcher(cells[0]).find()) {
                for (String key : PROFILE_KEYS) {
                    out.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(formId);
                }
            }
            Matcher key = METRIC_KEY.matcher(cells[0]);
            while (key.find()) {
                out.computeIfAbsent(key.group(1), k -> new LinkedHashSet<>()).add(formId);
            }
        }
        return out;
    }

    @Test
    @DisplayName("映射文档可解析（行首/行内指标键都能取到，含 §9 的 D1…D7 省略写法）")
    void mappingDocIsParsable() throws IOException {
        Map<String, Set<String>> assigned = assignedForms();
        System.out.println("== 图型指派对账（解析自 metric-chart-mapping.md）==");
        System.out.println("  文档登记的指标键 = " + assigned.size());
        List<String> keys = new ArrayList<>(assigned.keySet());
        System.out.println("  键样本 = " + keys.subList(0, Math.min(12, keys.size())));
        for (String must : List.of("M1a", "M2b", "M5a", "M5b", "M7a", "M7b", "M7d", "M7e",
                "M8a", "M8c", "M8d", "M8g", "D1_PACE", "D7_PERSIST")) {
            assertTrue(assigned.containsKey(must),
                    "映射文档解析结果缺 " + must + " —— 正则或文档结构变了，本守卫已失去意义");
        }
        assertTrue(assigned.size() >= 30, "解析到的指标键过少（" + assigned.size() + "），守卫可能空转");
    }

    @Test
    @DisplayName("实现指派的图型 ⊆ 映射文档指派的图型（逐条对账，不许自造）")
    void implementationMatchesMappingDoc() throws IOException {
        Map<String, Set<String>> assigned = assignedForms();
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : assigned.entrySet()) {
            String got = ChartForms.forMetric(e.getKey());
            if (!e.getValue().contains(got)) {
                wrong.add(e.getKey() + " 实现=" + got + " 文档=" + e.getValue());
            }
        }
        System.out.println("== 逐条对账 ==");
        System.out.println("  文档登记 " + assigned.size() + " 个键；不符 " + wrong.size());
        for (Map.Entry<String, Set<String>> e : assigned.entrySet()) {
            System.out.println(String.format("    %-10s 实现=%-4s 文档=%s",
                    e.getKey(), ChartForms.forMetric(e.getKey()), e.getValue()));
        }
        assertTrue(wrong.isEmpty(),
                "以下指标的图型指派与 metric-chart-mapping.md 不符（文档是唯一权威）：" + wrong);
    }

    @Test
    @DisplayName("实现返回的每个图型都在 C1–C17 全集内（防止拼错常量）")
    void everyFormIsInTheCatalogue() {
        for (String form : ChartForms.ALL_FORMS) {
            assertTrue(Pattern.matches("C([1-9]|1[0-7])", form), "非法图型代号：" + form);
        }
        assertFalse(ChartForms.ALL_FORMS.isEmpty());
        assertTrue(new LinkedHashSet<>(ChartForms.ALL_FORMS).size() == ChartForms.ALL_FORMS.size(),
                "图型全集有重复项");
    }
}
