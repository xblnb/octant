package com.octant.common.privacy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class RegistryCoverage {

    public record Report(int yamlFieldCount,
                         int yamlRawFieldCount,
                         int javaRawFieldCount,
                         Set<String> missingInJava,
                         Set<String> extraInJava,
                         Set<String> unknownTransforms,
                         Set<String> yamlTransforms,
                         Set<String> dataFieldNames,
                         Set<String> uncoveredDataFields) {

        public Report {
            missingInJava = Collections.unmodifiableSet(new TreeSet<>(missingInJava));
            extraInJava = Collections.unmodifiableSet(new TreeSet<>(extraInJava));
            unknownTransforms = Collections.unmodifiableSet(new TreeSet<>(unknownTransforms));
            yamlTransforms = Collections.unmodifiableSet(new TreeSet<>(yamlTransforms));
            dataFieldNames = Collections.unmodifiableSet(new TreeSet<>(dataFieldNames));
            uncoveredDataFields = Collections.unmodifiableSet(new TreeSet<>(uncoveredDataFields));
        }

        public boolean isFullyCovered() {
            return missingInJava.isEmpty() && extraInJava.isEmpty() && unknownTransforms.isEmpty();
        }

        public boolean needsPrivacyRuling() {
            return !uncoveredDataFields.isEmpty();
        }

        public String summary() {
            return "RegistryCoverage{yamlFields=" + yamlFieldCount
                    + ", yamlRawFields=" + yamlRawFieldCount
                    + ", javaRawFields=" + javaRawFieldCount
                    + ", yamlTransforms=" + yamlTransforms.size()
                    + ", dataFields=" + dataFieldNames.size()
                    + ", missingInJava=" + missingInJava.size()
                    + ", extraInJava=" + extraInJava.size()
                    + ", unknownTransforms=" + unknownTransforms.size()
                    + ", uncoveredDataFields=" + uncoveredDataFields.size() + "}";
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("yamlFieldCount", yamlFieldCount);
            m.put("yamlRawFieldCount", yamlRawFieldCount);
            m.put("javaRawFieldCount", javaRawFieldCount);
            m.put("yamlTransforms", List.copyOf(yamlTransforms));
            m.put("missingInJava", List.copyOf(missingInJava));
            m.put("extraInJava", List.copyOf(extraInJava));
            m.put("unknownTransforms", List.copyOf(unknownTransforms));
            m.put("dataFieldCount", dataFieldNames.size());
            m.put("uncoveredDataFields", List.copyOf(uncoveredDataFields));
            m.put("needsPrivacyRuling", needsPrivacyRuling());
            m.put("fullyCovered", isFullyCovered());
            return m;
        }
    }

    public record YamlEntry(String fieldId, String rawField, String locationClass, String transform) {
    }

    private RegistryCoverage() {
    }

    public static java.util.Optional<Path> locate() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("docs").resolve("privacy").resolve("field-registry.yaml");
            if (Files.isRegularFile(candidate)) {
                return java.util.Optional.of(candidate);
            }
            dir = dir.getParent();
        }
        return java.util.Optional.empty();
    }

    public static Report check() throws IOException {
        Path path = locate().orElseThrow(() -> new IOException(
                "未找到 docs/privacy/field-registry.yaml（覆盖率核对无法进行）"));
        return check(Files.readString(path, StandardCharsets.UTF_8));
    }

    public static Report check(String yamlText) {
        List<YamlEntry> entries = parse(yamlText);
        Set<String> yamlRaw = new LinkedHashSet<>();
        for (YamlEntry e : entries) {
            if (e.rawField() != null && !e.rawField().isBlank()) {
                yamlRaw.add(e.rawField());
            }
        }
        Set<String> javaRaw = FieldRegistry.registeredRawFields();
        Set<String> implementedTransforms = FieldRegistry.implementedTransformIds();
        Set<String> yamlTransforms = new TreeSet<>();
        Set<String> unknown = new TreeSet<>();
        for (YamlEntry e : entries) {
            if (e.transform() == null || e.transform().isBlank()) {
                continue;
            }
            yamlTransforms.add(e.transform());
            if (!implementedTransforms.contains(e.transform())) {
                unknown.add(e.transform());
            }
        }
        Set<String> missing = new TreeSet<>(yamlRaw);
        missing.removeAll(javaRaw);
        Set<String> extra = new TreeSet<>(javaRaw);
        extra.removeAll(yamlRaw);

        Set<String> dataFields = new TreeSet<>(
                com.octant.common.model.PayloadSchema.allowedFieldNames());
        Set<String> knownByName = FieldRegistry.knownFieldNameSuffixes();
        Set<String> uncovered = new TreeSet<>();
        for (String f : dataFields) {
            if (!knownByName.contains(f) && !knownByName.contains(toSnakeCase(f))) {
                uncovered.add(f);
            }
        }

        return new Report(entries.size(), yamlRaw.size(), javaRaw.size(), missing, extra, unknown,
                yamlTransforms, dataFields, uncovered);
    }

    static String toSnakeCase(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    static List<YamlEntry> parse(String yamlText) {
        List<YamlEntry> out = new java.util.ArrayList<>();
        String currentId = null;
        String currentRaw = null;
        String currentClass = null;
        String currentTransform = null;
        boolean inFields = false;
        boolean inTransforms = false;

        for (String rawLine : yamlText.split("\r?\n", -1)) {
            String line = stripComment(rawLine);
            if (line.isBlank()) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            String body = line.substring(indent);

            if (indent == 0) {
                if (body.startsWith("fields:")) {
                    inFields = true;
                    inTransforms = false;
                    continue;
                }
                if (body.startsWith("transforms:")) {
                    inTransforms = true;
                    inFields = false;
                    continue;
                }
                if (body.endsWith(":")) {
                    inFields = false;
                    inTransforms = false;
                }
                continue;
            }
            if (!inFields && !inTransforms) {
                continue;
            }
            if (body.startsWith("- ")) {
                if (inFields && currentId != null) {
                    out.add(new YamlEntry(currentId, currentRaw, currentClass, currentTransform));
                }
                currentId = null;
                currentRaw = null;
                currentClass = null;
                currentTransform = null;
                body = body.substring(2).trim();
                if (body.isEmpty()) {
                    continue;
                }
            }
            int colon = body.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = body.substring(0, colon).trim();
            String value = unquote(body.substring(colon + 1).trim());
            switch (key) {
                case "id" -> currentId = value;
                case "rawField" -> {
                    if (inFields) {
                        currentRaw = value;
                    }
                }
                case "locationClass" -> {
                    if (inFields) {
                        currentClass = value;
                    }
                }
                case "transform" -> {
                    if (inFields) {
                        currentTransform = value;
                    }
                }
                case "kind" -> {
                    if (inTransforms && currentId == null) {
                        currentId = value;
                    }
                }
                default -> {
                }
            }
        }
        if (inFields && currentId != null) {
            out.add(new YamlEntry(currentId, currentRaw, currentClass, currentTransform));
        }
        return out;
    }

    private static String stripComment(String line) {
        String trimmed = line.stripLeading();
        return trimmed.startsWith("#") ? "" : line;
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}
