package com.octant.common.privacy;

import com.octant.common.model.ContractException;
import com.octant.common.platforms.PlatformCapabilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class PackSnapshot {

    public static final int MIN_PLAYERS_FOR_GROUP_CONCLUSION = 5;

    public enum PackKind {
        RESOURCE("rp#", "resourcepack"),
        DATA("dp#", "datapack"),
        MOD("", "mod");

        private final String prefix;
        private final String wire;

        PackKind(String prefix, String wire) {
            this.prefix = prefix;
            this.wire = wire;
        }

        public String prefix() {
            return prefix;
        }

        public String wire() {
            return wire;
        }
    }

    public enum SetKind {
        VANILLA_ONLY("vanilla_only"),
        PUBLIC_ADDONS_ONLY("public_addons_only"),
        CUSTOM_PACKS_PRESENT("custom_packs_present"),
        UNKNOWN("unknown");

        private final String wire;

        SetKind(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    public enum SourceCategory {
        T1_PUBLIC_VERIFIED("T1"),
        T2_PUBLIC_UNVERIFIED("T2"),
        T3_CUSTOM_SOURCE("T3"),
        T4_SELF_AUTHORED("T4"),
        T5_BUILTIN("T5");

        private final String wire;

        SourceCategory(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    public enum RepresentationForm {
        MOD_ID_VERBATIM("pack.mod_id"),
        MOD_CONTENT_HASH("pack.mod_content_hash"),
        RESOURCEPACK_DIGEST("pack.resourcepack_id"),
        DATAPACK_DIGEST("pack.datapack_id"),
        DATAPACK_BUILTIN("pack.datapack_id"),
        SET_FINGERPRINT("pack.set_fingerprint"),
        SET_CLASS("pack.set_class"),
        UNAVAILABLE("unavailable");

        private final String wire;

        RepresentationForm(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public boolean requiresGroupThreshold() {
            return this == SET_FINGERPRINT;
        }
    }

    public static final String BUILTIN_DATAPACK_ID = "dp#builtin";

    public static final String SOURCE_UNAVAILABLE_ON_PLATFORM = "SOURCE_UNAVAILABLE_ON_PLATFORM";

    public static RepresentationForm representationOf(SourceCategory cat, PackKind kind, boolean builtin) {
        if (kind == PackKind.DATA && builtin) {
            return RepresentationForm.DATAPACK_BUILTIN;
        }
        if (kind == PackKind.MOD) {
            if (cat == SourceCategory.T1_PUBLIC_VERIFIED) {
                return RepresentationForm.MOD_ID_VERBATIM;
            }
            if (cat == SourceCategory.T3_CUSTOM_SOURCE || cat == SourceCategory.T4_SELF_AUTHORED) {
                return RepresentationForm.MOD_CONTENT_HASH;
            }
            throw new ContractException("来源类别 " + cat.wire() + " 对 mod 未在 14 行清单中登记");
        }
        return kind == PackKind.RESOURCE
                ? RepresentationForm.RESOURCEPACK_DIGEST
                : RepresentationForm.DATAPACK_DIGEST;
    }

    public static boolean isModIdPermitted(SourceCategory cat) {
        return cat == SourceCategory.T1_PUBLIC_VERIFIED;
    }

    public static String sizeBucket(int totalPacks) {
        if (totalPacks <= 0) {
            return "0";
        }
        if (totalPacks <= 10) {
            return "1_10";
        }
        if (totalPacks <= 30) {
            return "11_30";
        }
        if (totalPacks <= 60) {
            return "31_60";
        }
        return "61_plus";
    }

    public record PackEntry(String packId, String kindWire, int fileCount, long totalBytes) {

        public PackEntry {
            if (packId == null || !packId.matches("(rp#|dp#)[0-9a-f]{16}")) {
                throw new ContractException(
                        "packId 必须形如 rp#/dp# + 16 位小写十六进制（privacy 登记）：" + packId);
            }
            if (fileCount < 0 || totalBytes < 0) {
                throw new ContractException("fileCount/totalBytes 不得为负");
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("packId", packId);
            m.put("kind", kindWire);
            m.put("fileCount", fileCount);
            m.put("totalBytes", totalBytes);
            return m;
        }
    }

    private final SaltProvider salt;
    private final List<PackEntry> resourcePacks = new ArrayList<>();
    private final List<PackEntry> dataPacks = new ArrayList<>();
    private final List<Map<String, Object>> mods = new ArrayList<>();

    private boolean enumerationAvailable = false;

    public PackSnapshot(SaltProvider salt) {
        this.salt = java.util.Objects.requireNonNull(salt, "salt");
    }

    public PackSnapshot setEnumerationAvailable(PlatformCapabilities.Combo combo) {
        return setEnumerationAvailability(
                PlatformCapabilities.packEnumerationAvailability(
                        java.util.Objects.requireNonNull(combo, "combo")));
    }

    private PackSnapshot setEnumerationAvailability(
            PlatformCapabilities.Availability availability) {
        this.enumerationAvailable = (availability == PlatformCapabilities.Availability.VERIFIED);
        return this;
    }

    public boolean enumerationAvailable() {
        return enumerationAvailable;
    }

    public PackEntry addResourcePack(String contentHash, int fileCount, long totalBytes) {
        PackEntry e = new PackEntry(pseudonymize(PackKind.RESOURCE, contentHash), PackKind.RESOURCE.wire(),
                fileCount, totalBytes);
        resourcePacks.add(e);
        return e;
    }

    public PackEntry addDataPack(String contentHash, int fileCount, long totalBytes) {
        PackEntry e = new PackEntry(pseudonymize(PackKind.DATA, contentHash), PackKind.DATA.wire(),
                fileCount, totalBytes);
        dataPacks.add(e);
        return e;
    }

    public void addMod(SourceCategory sourceCategory, String modId, String contentHash, String version) {
        RepresentationForm form = representationOf(sourceCategory, PackKind.MOD, false);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sourceCategory", sourceCategory.wire());
        switch (form) {
            case MOD_ID_VERBATIM -> {
                if (modId == null || modId.isBlank()) {
                    throw new ContractException("T1 模组必须提供 modid（形态为 id@version）");
                }
                if (contentHash != null && !contentHash.isBlank()) {
                    throw new ContractException(
                            "T1 模组**不得**输出内容哈希（privacy modPermission：哈希会增加跨导出可链接面）");
                }
                m.put("modId", requireIdShaped(modId));
            }
            case MOD_CONTENT_HASH -> {
                if (modId != null && !modId.isBlank()) {
                    throw new ContractException("T3/T4 模组**绝不输出 modid**，只出截断哈希 + 版本");
                }
                if (contentHash == null || contentHash.isBlank()) {
                    throw new ContractException("T3/T4 模组必须提供内容哈希（否则无法去重与归因）");
                }
                m.put("modHash", salt.pseudonym("mod", contentHash));
            }
            default -> throw new ContractException("mod 的表示形态未登记：" + form);
        }
        m.put("version", version == null ? "" : version);
        mods.add(Collections.unmodifiableMap(m));
    }

    public static Map<String, Object> unavailableEntry(String what) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "unavailable");
        m.put("reasonCode", SOURCE_UNAVAILABLE_ON_PLATFORM);
        m.put("what", what);
        return m;
    }

    public String environmentDigest16(String canonicalDetail) {
        if (canonicalDetail == null || canonicalDetail.isEmpty()) {
            throw new ContractException(
                    "环境明细规范化串不得为空（空串的摘要是常量，等于无摘要 ⇒ 无法判定\"同一份\"）");
        }
        return salt.pseudonym("envDetail", canonicalDetail);
    }

    public String setFingerprintOrNull(int contributingPlayers) {
        if (contributingPlayers < MIN_PLAYERS_FOR_GROUP_CONCLUSION) {
            return null;
        }
        Set<String> all = new TreeSet<>();
        for (PackEntry e : resourcePacks) {
            all.add(e.packId());
        }
        for (PackEntry e : dataPacks) {
            all.add(e.packId());
        }
        return salt.pseudonym("set", String.join("|", all));
    }

    public SetKind setKind(boolean hasCustomPacks) {
        if (!this.enumerationAvailable) {
            return SetKind.UNKNOWN;
        }
        if (resourcePacks.isEmpty() && dataPacks.isEmpty() && mods.isEmpty()) {
            return SetKind.VANILLA_ONLY;
        }
        return hasCustomPacks ? SetKind.CUSTOM_PACKS_PRESENT : SetKind.PUBLIC_ADDONS_ONLY;
    }

    public int totalPacks() {
        return resourcePacks.size() + dataPacks.size();
    }

    public int resourcePackCount() {
        return resourcePacks.size();
    }

    public int dataPackCount() {
        return dataPacks.size();
    }

    public int modCount() {
        return mods.size();
    }

    public Map<String, Integer> modCountBySourceClass() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> m : mods) {
            Object cat = m.get("sourceCategory");
            if (cat == null) {
                continue;
            }
            counts.merge(String.valueOf(cat), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", 1);
        List<Object> rp = new ArrayList<>();
        for (PackEntry e : resourcePacks) {
            rp.add(e.toMap());
        }
        List<Object> dp = new ArrayList<>();
        for (PackEntry e : dataPacks) {
            dp.add(e.toMap());
        }
        m.put("resourcepacks", rp);
        m.put("datapacks", dp);
        m.put("mods", List.copyOf(mods));
        m.put("packCount", totalPacks());
        m.put("sizeBucket", sizeBucket(totalPacks()));
        return m;
    }

    public Map<String, Object> toExportForm(int contributingPlayers, boolean hasCustomPacks) {
        Map<String, Object> m = new LinkedHashMap<>(toMap());
        String fp = setFingerprintOrNull(contributingPlayers);
        if (fp == null) {
            Map<String, Object> sup = new LinkedHashMap<>();
            sup.put("status", "suppressed");
            sup.put("reasonCode", "INSUFFICIENT_GROUP_SIZE");
            sup.put("suppressedByRule", "RULE-3");
            m.put("setFingerprintSuppressed", sup);
        } else {
            m.put("setFingerprint", fp);
        }
        Map<String, Object> cls = new LinkedHashMap<>();
        cls.put("kind", setKind(hasCustomPacks).wire());
        cls.put("sizeBucket", sizeBucket(totalPacks()));
        if (!enumerationAvailable) {
            cls.put("enumerationUnavailable", unavailableEntry("packList"));
        }
        m.put("setClass", cls);
        return m;
    }

    public static List<String> neverCollectedFields() {
        return List.of("fileName", "displayName", "description", "icon", "packTitle");
    }

    private String pseudonymize(PackKind kind, String contentHash) {
        if (contentHash == null || contentHash.isBlank()) {
            throw new ContractException("contentHash 不得为空（包标识只能由内容派生）");
        }
        return kind.prefix() + salt.pseudonym(kind.wire(), contentHash);
    }

    private static String requireIdShaped(String modId) {
        if (modId.length() > 128) {
            throw new ContractException("modId 超长：" + modId.length());
        }
        for (int i = 0; i < modId.length(); i++) {
            char c = modId.charAt(i);
            if (c <= ' ' || Character.isISOControl(c)) {
                throw new ContractException("modId 含空白或控制字符（禁止自由文本）");
            }
        }
        return modId.toLowerCase(Locale.ROOT);
    }
}
