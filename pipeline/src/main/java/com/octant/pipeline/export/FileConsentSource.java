package com.octant.pipeline.export;

import com.octant.pipeline.json.Json;
import com.octant.pipeline.json.JsonReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class FileConsentSource implements ExportPipeline.ConsentSource {

    public static final List<String> SUPPORTED_SCHEMA_VERSIONS = List.of(
            "privacy-consent@1.0.0", "privacy-consent@2.0.0");

    private final Path consentFile;

    public FileConsentSource(Path consentFile) {
        this.consentFile = consentFile;
    }

    @Override
    public ExportPipeline.ConsentState read() throws Exception {
        if (consentFile == null) {
            throw new IOException("同意状态文件路径未提供");
        }
        if (!Files.isRegularFile(consentFile)) {
            throw new IOException("同意状态文件不存在：" + consentFile);
        }
        String text;
        try {
            text = Files.readString(consentFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException("同意状态文件不可读：" + consentFile, e);
        }
        if (text.isBlank()) {
            throw new IllegalStateException("同意状态文件为空（视为解析失败）：" + consentFile);
        }
        Json.JsonObject root;
        try {
            root = JsonReader.parseObject(text);
        } catch (RuntimeException e) {
            throw new IllegalStateException("同意状态 JSON 解析失败：" + e.getMessage(), e);
        }

        String schemaVersion = root.str("schemaVersion");
        if (schemaVersion == null || !SUPPORTED_SCHEMA_VERSIONS.contains(schemaVersion)) {
            throw new IllegalArgumentException("同意状态 schemaVersion 不识别："
                    + schemaVersion + "（支持 " + SUPPORTED_SCHEMA_VERSIONS + "）");
        }
        Json.JsonObject permit = root.get("permit") instanceof Json.JsonObject p ? p : root;
        boolean collectionEnabled = requireBool(permit, "enabled");
        boolean exportPermitted = requireBool(permit, "exportEnabled");
        boolean acknowledged = requireBool(permit, "acknowledged");

        List<String> categories = new ArrayList<>();
        Object raw = permit.get("consentedCategories");
        if (raw instanceof Json.JsonArray arr) {
            for (Object item : arr.items()) {
                if (item instanceof String s) {
                    categories.add(s);
                }
            }
        }
        return new ExportPipeline.ConsentState(collectionEnabled, exportPermitted, acknowledged, categories);
    }

    private static boolean requireBool(Json.JsonObject o, String key) {
        Object v = o.get(key);
        if (!(v instanceof Boolean b)) {
            throw new IllegalArgumentException("同意状态缺少布尔字段 " + key + "（实际 "
                    + (v == null ? "缺失" : v.getClass().getSimpleName()) + "）");
        }
        return b;
    }
}
