package com.octant.pipeline.export;

import com.octant.common.privacy.FieldRegistry;

import java.util.List;

public final class RedactionMatrix {

    public enum Transformation {
        KEEP, PSEUDONYMIZE, ID_WHITELIST, DROP
    }

    public static final List<String> FORBIDDEN_FIELDS = List.of(
            "playerKey", "player.uuid", "player.name", "player.profileId", "player.skin", "player.cape",
            "entity.uuid", "entity.customName", "world.levelId", "world.path", "fs.path",
            "server.address", "server.motd", "client.ip", "client.hostname", "hardware.userName",
            "chat.message", "cmd.raw", "cmd.selector", "item.bookText", "block.signText",
            "pos.x", "pos.y", "pos.z", "time.epochMs", "playerUuid");

    public static final List<String[]> T1_PATTERNS = List.of(
            new String[]{"uuid", "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"},
            new String[]{"ipv4", "\\b(?:25[0-5]|2[0-4]\\d|1\\d\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}\\b"},
            new String[]{"url", "(?:https?|ftp)://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+"},
            new String[]{"winpath", "[A-Za-z]:\\\\(?:[A-Za-z0-9_. -]+\\\\)+"},
            new String[]{"unixpath", "/(?:home|Users|root|opt|etc|var|srv)/[A-Za-z0-9._/-]+"},
            new String[]{"email", "[线性扫描器（见 LinearScanners）：[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,} 的等价形态]"},
            new String[]{"hostport", "\\b(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}:(?:\\d{2,5})\\b"},
            new String[]{"ipv6", "\\b(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{1,4}\\b"},
            new String[]{"mcserver", "\\bmc\\.[a-z0-9-]+\\.[a-z]{2,}(?::\\d+)?\\b"});

    public static List<int[]> linearHits(String patternClass, String text) {
        if (!"email".equals(patternClass)) {
            return List.of();
        }
        return LinearScanners.emailHits(text);
    }

    public static boolean isLinearScan(String patternClass) {
        return "email".equals(patternClass);
    }

    public static List<String> allPatternClasses() {
        List<String> out = new java.util.ArrayList<>();
        for (String[] p : T1_PATTERNS) {
            out.add(p[0]);
        }
        return out;
    }

    public static int firstHit(String patternClass, String text) {
        if (text == null || patternClass == null) {
            return -1;
        }
        if (isLinearScan(patternClass)) {
            List<int[]> hits = linearHits(patternClass, text);
            return hits.isEmpty() ? -1 : hits.get(0)[0];
        }
        for (String[] p : T1_PATTERNS) {
            if (p[0].equals(patternClass)) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(p[1]).matcher(text);
                return m.find() ? m.start() : -1;
            }
        }
        throw new IllegalArgumentException("T1 扫描类未登记（不得静默放过）：" + patternClass);
    }

    private RedactionMatrix() {
    }

    public static Transformation transformationFor(String fieldName) {
        java.util.Optional<FieldRegistry.FieldPolicy> effective = resolveEffectivePolicy(fieldName);
        if (effective.isPresent()) {
            return tierOf(effective.get());
        }
        return switch (fieldName) {
            case "playerKey" -> Transformation.PSEUDONYMIZE;
            case "player.uuid", "player.name", "player.profileId", "player.skin", "player.cape",
                 "entity.uuid", "entity.customName", "world.levelId", "world.path", "fs.path",
                 "server.address", "server.motd", "client.ip", "client.hostname", "hardware.userName",
                 "chat.message", "cmd.raw", "cmd.selector", "item.bookText", "block.signText",
                 "pos.x", "pos.y", "pos.z", "time.epochMs" -> Transformation.DROP;
            default -> isContractFieldName(fieldName) ? Transformation.KEEP : Transformation.DROP;
        };
    }

    private static Transformation tierOf(FieldRegistry.FieldPolicy p) {
        if (p.mustNotBeCollected() || p.transform().isDrop()) {
            return Transformation.DROP;
        }
        if (p.transform().isIrreversible()) {
            return Transformation.PSEUDONYMIZE;
        }
        if (p.transform() == FieldRegistry.Transform.KEEP_ID_WHITELIST) {
            return Transformation.ID_WHITELIST;
        }
        return Transformation.KEEP;
    }

    public static boolean isContractFieldName(String fieldName) {
        return fieldName != null
                && com.octant.common.model.PayloadSchema.allowedFieldNames().contains(fieldName);
    }

    public static boolean isFailClosedDrop(String fieldName) {
        if (resolveEffectivePolicy(fieldName).isPresent()) {
            return false;
        }
        if (isStaticallyForbiddenByName(fieldName)) {
            return false;
        }
        return !isContractFieldName(fieldName);
    }

    public static boolean isContractKnownButUnregistered(String fieldName) {
        return resolveEffectivePolicy(fieldName).isEmpty()
                && !isStaticallyForbiddenByName(fieldName)
                && isContractFieldName(fieldName);
    }

    private static boolean isStaticallyForbiddenByName(String fieldName) {
        return switch (String.valueOf(fieldName)) {
            case "player.uuid", "player.name", "player.profileId", "player.skin", "player.cape",
                 "entity.uuid", "entity.customName", "world.levelId", "world.path", "fs.path",
                 "server.address", "server.motd", "client.ip", "client.hostname", "hardware.userName",
                 "chat.message", "cmd.raw", "cmd.selector", "item.bookText", "block.signText",
                 "pos.x", "pos.y", "pos.z", "time.epochMs" -> true;
            default -> false;
        };
    }

    static java.util.Optional<FieldRegistry.FieldPolicy> resolveEffectivePolicy(String fieldName) {
        for (FieldRegistry.FieldPolicy p : FieldRegistry.allPoliciesForName(fieldName)) {
            if (FieldRegistry.isDeprecated(p)) {
                continue;
            }
            return java.util.Optional.of(p);
        }
        return java.util.Optional.empty();
    }

    public static boolean isIdField(String fieldName) {
        return switch (fieldName) {
            case "advancementId", "parentId", "recipeId", "item", "dimension", "fromDimension",
                 "biome", "structure", "machineBlock", "outputItem",
                 "entityType", "deathCause", "killerEntityType", "station", "unitId", "questId",
                 "chapterId", "questSource" -> true;
            default -> false;
        };
    }

    public static boolean isQuantizedX(long x) {
        return x == Math.floorDiv(x, 512L) * 512L;
    }

    public static boolean isQuantizedY(long y) {
        return y == Math.floorDiv(y, 32L) * 32L;
    }

    public static long quantizeX(long x) {
        return Math.floorDiv(x, 512L) * 512L;
    }

    public static long quantizeY(long y) {
        return Math.floorDiv(y, 32L) * 32L;
    }
}
