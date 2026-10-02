package com.octant.common.privacy;

import com.octant.common.model.ContractException;
import com.octant.common.model.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Transforms {

    public static final int QUANT_HORIZONTAL = 512;

    public static final int QUANT_VERTICAL = 32;

    public static final int CUSTOM_LABEL_MAX_CHARS = 64;

    private Transforms() {
    }

    public static long quantizeHorizontal(long v) {
        return (v >> 9) << 9;
    }

    public static long quantizeVertical(long v) {
        return (v >> 5) << 5;
    }

    public static long quantizeHorizontalWithOffset(long v, int offset) {
        if (offset < 0 || offset >= QUANT_HORIZONTAL) {
            throw new ContractException("水平随机偏移必须在 [0," + QUANT_HORIZONTAL + ")：" + offset);
        }
        return quantizeHorizontal(v) + offset;
    }

    public static long quantizeVerticalWithOffset(long v, int offset) {
        if (offset < 0 || offset >= QUANT_VERTICAL) {
            throw new ContractException("垂直随机偏移必须在 [0," + QUANT_VERTICAL + ")：" + offset);
        }
        return quantizeVertical(v) + offset;
    }

    public static String regionKey(String dimension, long x, long z) {
        if (dimension == null || dimension.isBlank()) {
            throw new ContractException("dimension 不得为空");
        }
        return dimension + "#" + (x >> 9) + ":" + (z >> 9);
    }

    public static String quantizeTruncateLabel(String label) {
        if (label == null) {
            return "";
        }
        String nfc = java.text.Normalizer.normalize(label, java.text.Normalizer.Form.NFC);
        StringBuilder sb = new StringBuilder(nfc.length());
        for (int i = 0; i < nfc.length(); i++) {
            char c = nfc.charAt(i);
            if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF') {
                continue;
            }
            sb.append(c);
        }
        String cleaned = sb.toString().trim();
        return cleaned.length() <= CUSTOM_LABEL_MAX_CHARS
                ? cleaned
                : cleaned.substring(0, CUSTOM_LABEL_MAX_CHARS);
    }

    public static String ramBucket(long bytes) {
        long gb = bytes / (1024L * 1024L * 1024L);
        if (gb <= 2) {
            return "2";
        }
        if (gb <= 4) {
            return "4";
        }
        if (gb <= 8) {
            return "8";
        }
        if (gb <= 16) {
            return "16";
        }
        return "32+";
    }

    public static String osFamily(String osName) {
        if (osName == null) {
            return "other";
        }
        String s = osName.toLowerCase(Locale.ROOT);
        if (s.contains("win")) {
            return "windows";
        }
        if (s.contains("mac") || s.contains("darwin")) {
            return "macos";
        }
        if (s.contains("linux") || s.contains("unix")) {
            return "linux";
        }
        return "other";
    }

    public static String gpuFamily(String gpuName) {
        if (gpuName == null) {
            return "other";
        }
        String s = gpuName.toLowerCase(Locale.ROOT);
        if (s.contains("nvidia") || s.contains("geforce") || s.contains("quadro") || s.contains("rtx")
                || s.contains("gtx")) {
            return "nvidia";
        }
        if (s.contains("intel") || s.contains("arc ") || s.contains("uhd graphics")) {
            return "intel";
        }
        if (s.contains("amd") || s.contains("radeon") || s.contains("ati ")) {
            return "amd";
        }
        if (s.contains("apple") || s.contains("m1") || s.contains("m2") || s.contains("m3")) {
            return "apple";
        }
        return "other";
    }

    public static String privacyClass(String rawAddressOrMode) {
        if (rawAddressOrMode == null) {
            return "unknown";
        }
        String s = rawAddressOrMode.trim().toLowerCase(Locale.ROOT);
        for (String allowed : List.of("singleplayer", "private_server", "public_server", "unknown")) {
            if (allowed.equals(s)) {
                return allowed;
            }
        }
        if (s.isEmpty()) {
            return "unknown";
        }
        if (s.contains("localhost") || s.startsWith("192.168.") || s.startsWith("10.")
                || s.startsWith("172.16.")) {
            return "private_server";
        }
        if (s.contains(":") || s.contains(".")) {
            return "public_server";
        }
        return "unknown";
    }

    public static String relabelSequence(int ordinal) {
        if (ordinal < 1) {
            throw new ContractException("重标记序号必须从 1 起：" + ordinal);
        }
        return "P" + ordinal;
    }

    public static String keepIdOnly(String text) {
        if (text == null) {
            return "";
        }
        String s = text.trim();
        if (s.isEmpty() || s.length() > 128) {
            return "";
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= ' ' || Character.isISOControl(c)) {
                return "";
            }
        }
        return s;
    }

    public static String utcDate(long epochMillis) {
        return java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(epochMillis),
                java.time.ZoneOffset.UTC).toString();
    }

    public static long relativize(long epochMillis, long sessionAnchorMillis) {
        return Math.max(0L, epochMillis - sessionAnchorMillis);
    }

    public static String languageMainTag(String bcp47) {
        if (bcp47 == null || bcp47.isBlank()) {
            return "";
        }
        String trimmed = bcp47.trim();
        int cut = trimmed.length();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '-' || c == '_') {
                cut = i;
                break;
            }
        }
        String main = trimmed.substring(0, cut).toLowerCase(Locale.ROOT);
        return main.matches("[a-z]{2,8}") ? main : "";
    }

    public static String dayBucket(int localHourOfDay) {
        if (localHourOfDay < 0 || localHourOfDay > 23) {
            throw new ContractException("本地小时数必须在 0–23：" + localHourOfDay);
        }
        if (localHourOfDay < 6) {
            return "night";
        }
        if (localHourOfDay < 12) {
            return "morning";
        }
        if (localHourOfDay < 18) {
            return "afternoon";
        }
        return "evening";
    }

    public static String lengthBucket(String text) {
        int n = text == null ? 0 : text.codePointCount(0, text.length());
        if (n == 0) {
            return "0";
        }
        if (n <= 8) {
            return "1-8";
        }
        if (n <= 32) {
            return "9-32";
        }
        return "33+";
    }

    public static String reasonDetail(String reasonCode, String observed, String required) {
        if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]*")) {
            throw new ContractException("原因码必须是闭集内的 UPPER_SNAKE_CASE：" + reasonCode);
        }
        String detail = reasonCode + ": observed=" + observed + " required=" + required;
        if (!isAsciiDetail(detail)) {
            throw new ContractException("detail 必须是纯 ASCII 结构化格式（禁止自由文本）：" + detail);
        }
        return detail;
    }

    public static boolean isAsciiDetail(String detail) {
        if (detail == null) {
            return false;
        }
        for (int i = 0; i < detail.length(); i++) {
            char c = detail.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }

    public static TruncatedDigest truncateDigest(List<Map<String, Object>> digest, int maxElements) {
        List<Map<String, Object>> copy = new ArrayList<>(digest == null ? List.of() : digest);
        if (copy.size() <= maxElements) {
            return new TruncatedDigest(List.copyOf(copy), false);
        }
        copy.sort((a, b) -> Long.compare(asCount(b), asCount(a)));
        return new TruncatedDigest(List.copyOf(copy.subList(0, maxElements)), true);
    }

    private static long asCount(Map<String, Object> item) {
        for (String key : List.of("count", "missingCount")) {
            Object v = item.get(key);
            if (v instanceof Number n) {
                return n.longValue();
            }
        }
        return 0L;
    }

    public record TruncatedDigest(List<Map<String, Object>> items, boolean truncated) {
    }

    public static Map<String, Object> dropPlaceholder(FieldRegistry.Transform transform) {
        if (!transform.isDrop()) {
            throw new ContractException("仅丢弃型变换可生成占位声明：" + transform.specId());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dropped", true);
        m.put("transform", transform.specId());
        return m;
    }

    public static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "string(len=" + s.codePointCount(0, s.length()) + ")";
        }
        return value.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    public static String toJson(Object value) {
        return Json.encode(value);
    }
}
