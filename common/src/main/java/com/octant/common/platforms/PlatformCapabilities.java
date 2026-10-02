package com.octant.common.platforms;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PlatformCapabilities {

    public enum Loader {
        FORGE("forge"),
        NEOFORGE("neoforge"),
        FABRIC("fabric");

        private final String code;

        Loader(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public enum McVersion {
        V1_20_1("1.20.1"),
        V1_21_1("1.21.1");

        private final String wire;

        McVersion(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    public record Combo(Loader loader, McVersion mc) {

        public static Combo of(Loader l, McVersion v) {
            return new Combo(l, v);
        }

        @Override
        public String toString() {
            return loader.code() + "×" + mc.wire();
        }
    }

    public enum Availability {
        VERIFIED,
        UNVERIFIED,
        UNAVAILABLE
    }

    public record Capability(String what, Availability availability, String evidence, String notes) {

        public Capability {
            if (what == null || what.isBlank()) {
                throw new IllegalArgumentException("what 不得为空");
            }
            if (availability == Availability.VERIFIED && (evidence == null || evidence.isBlank())) {
                throw new IllegalArgumentException(
                        "标 VERIFIED 必须给出证据（jar 内省 / 实跑命令与退出码），否则就是凭印象断言：" + what);
            }
        }
    }

    private static final Map<Combo, List<Capability>> TABLE = build();

    private PlatformCapabilities() {
    }

    private static Map<Combo, List<Capability>> build() {
        Map<Combo, List<Capability>> t = new LinkedHashMap<>();

        t.put(Combo.of(Loader.NEOFORGE, McVersion.V1_21_1), List.of(
                new Capability("模组清单（modId + version）", Availability.VERIFIED,
                        "javap 于 fancymodloader/loader-4.0.44.jar："
                                + "net.neoforged.fml.ModList.get(): List<IModInfo>；ModContainer.getModId()",
                        "粒度：modId + version，与 registry 的 [{modId, version}] 形态一致"),
                new Capability("资源包 / 数据包清单与标识", Availability.VERIFIED,
                        "jar tf 于 neoforge-21.1.251-merged.jar：vanilla 包 API "
                                + "（PackRepository / Pack，位于 MC 客户端的 server.packs.repository 包）",
                        "**不是加载器能力，而是 Minecraft 层** ⇒ 三加载器共用同一套 API，包构成不必按加载器分叉"),
                new Capability("数据包清单", Availability.UNAVAILABLE,
                        "javap 显示 data pack 侧需要 MinecraftServer 上下文（DataPackConfig / server pack repository）",
                        "单机存档在**集成服**可拿；**纯客户端早期阶段拿不到** ⇒ 属「能力 × 生命周期」事实，"
                                + "不是平台差异。客户端模组形态下这是**主路径限制**，不是脚注"),
                new Capability("存档 NBT（level.dat / playerdata）", Availability.VERIFIED,
                        "jar tf 于 merged jar：NbtIo / NbtUtils（MC 的 nbt 包）、"
                                + "LevelStorageSource、PlayerDataStorage（MC 的 world.level.storage 包）",
                        "只证明**基础类齐备**；只读性、是否需要服务端上下文、脱机解析均未实测"),
                new Capability("存档内 advancements/*.json 与 stats/*.json", Availability.VERIFIED,
                        "属存档目录下的**普通 JSON 文件**，不经过任何加载器 API（纯文件读取）",
                        "**最容易拿且最稳的一类**；且它**不依赖平台矩阵** ⇒ 逐加载器能力表在它上面只需一行"),
                new Capability("运行时注册表全量枚举（所有 ID）", Availability.UNVERIFIED,
                        "",
                        "**这一类比 ModList 更重要**：ModList 只给包清单，注册表给内容清单 —— "
                                + "后者才是『不给成千上万模组写模板』的基础。未内省 MC 侧 ⇒ 不标 VERIFIED。"
                                + "隐私：注册表枚举会拿到**全部** ID，**含私有模组的自定义命名空间** ⇒ "
                                + "必须走 T1–T5 归一化；从前是手工挑的、现在是全量拿的，归一化重要性上升")));

        t.put(Combo.of(Loader.FABRIC, McVersion.V1_21_1), List.of(
                new Capability("模组清单（modId + version）", Availability.VERIFIED,
                        "javap 于 fabric-loader-0.19.5.jar：FabricLoader.getAllMods(): Collection<ModContainer>；"
                                + "ModContainer.getMetadata(): ModMetadata 且有 getId()/getVersion()",
                        "粒度与 NeoForge 一致（modId + version）⇒ registry 形态两侧通用"),
                new Capability("资源包 / 数据包", Availability.UNVERIFIED,
                        "",
                        "Loom 已能编译该组合，但本次**未内省 MC 侧**，故不标 VERIFIED"),
                new Capability("存档 NBT", Availability.UNVERIFIED, "",
                        "同属 Minecraft 层，预期与 NeoForge 一致，但**未实测就不写 VERIFIED**"),
                new Capability("运行时注册表全量枚举（所有 ID）", Availability.UNVERIFIED, "",
                        "同属 MC 层；未内省。隐私同 NeoForge 行的归一化要求"),
                new Capability("辅助模组（JEI/Xaero/KubeJS）的落盘形态", Availability.UNVERIFIED, "",
                        "批次 1 的四个 jar 是 **Fabric × 1.20.1**、不是本组合；且只做过 jar 内省、未实跑。"
                                + "与组合正交的逐模组事实见 {@link #auxModFacts()}")));

        for (Combo c : List.of(
                Combo.of(Loader.NEOFORGE, McVersion.V1_20_1),
                Combo.of(Loader.FABRIC, McVersion.V1_20_1),
                Combo.of(Loader.FORGE, McVersion.V1_20_1),
                Combo.of(Loader.FORGE, McVersion.V1_21_1))) {
            t.put(c, List.of(
                    new Capability("全部环境与存档数据", Availability.UNVERIFIED, "",
                            "本机无该组合的加载器 API jar；"
                                    + "Forge 的两处另有硬阻塞（FG7 需 Gradle 9.3.0；"
                                    + "1.20.1 侧曾卡在自家下载器的停滞连接）⇒ 按证据纪律标 UNVERIFIED")));
        }
        return java.util.Collections.unmodifiableMap(t);
    }

    public static List<Capability> capabilitiesOf(Combo combo) {
        return TABLE.getOrDefault(combo, List.of());
    }

    public record AuxModFact(String modId, String version, Loader loader, McVersion mc,
                             String diskForm, Availability availability, String evidence) {

        public AuxModFact {
            if (modId == null || modId.isBlank()) {
                throw new IllegalArgumentException("modId 不得为空");
            }
            if (availability == Availability.VERIFIED && (evidence == null || evidence.isBlank())) {
                throw new IllegalArgumentException("标 VERIFIED 必须给出证据：" + modId);
            }
        }
    }

    public static List<AuxModFact> auxModFacts() {
        return java.util.List.of(
                new AuxModFact("jei", "15.62.0.216", Loader.FABRIC, McVersion.V1_20_1,
                        "包体积内省：`fabric.mod.json`(env=*)、`jei.accesswidener`、`jei.mixins.json`、"
                                + "内嵌 `mezz_config`。**未在 jar 内发现数据目录** ⇒ 配方数据非随 jar 分发",
                        Availability.UNVERIFIED,
                        "jar tf 实测：fabric.mod.json=1、accesswidener=1、mixins.json=1"),
                new AuxModFact("xaeroworldmap", "1.46.0", Loader.FABRIC, McVersion.V1_20_1,
                        "两个 mixins 配置 + 内嵌 `xaerolib`。落盘形态**未实测**",
                        Availability.UNVERIFIED,
                        "jar tf 实测：fabric.mod.json=1、xaeroworldmap.mixins.json + xaeroworldmap.fabric.mixins.json"),
                new AuxModFact("xaerominimap", "26.5.0", Loader.FABRIC, McVersion.V1_20_1,
                        "四个 mixins 配置（含 xaerohud）+ 内嵌 `xaerolib`。落盘形态**未实测**",
                        Availability.UNVERIFIED,
                        "jar tf 实测：fabric.mod.json=1、xaerohud/xaerominimap 各两个 mixins.json"),
                new AuxModFact("kubejs", "2001.6.5-build.26", Loader.FABRIC, McVersion.V1_20_1,
                        "**脚本引擎**：`architectury_inject_*_common_*` + `kubejs-common.mixins.json` + "
                                + "`kubejs-fabric.mixins.json` + `kubejs.accesswidener`。"
                                + "『脚本生成的内容』是自动探测最易漏的一类，其落盘形态**未实测**",
                        Availability.UNVERIFIED,
                        "jar tf 实测：fabric.mod.json=1（id=kubejs）、mods.toml=0、architectury_inject 目录 1 个"),
                new AuxModFact("ftbquests", "2001.4.22", Loader.FORGE, McVersion.V1_20_1,
                        "**Loader 归属 = Forge**（`META-INF/mods.toml`），**与上面四个不能装在同一实例**；"
                                + "其目标组合 Forge × 1.20.1 本机**当前不可构建** ⇒ 无法勘察",
                        Availability.UNVERIFIED,
                        "jar tf 实测：mods.toml=1（modId=ftbquests）、fabric.mod.json=0"));
    }

    public static Map<Combo, List<Capability>> all() {
        return TABLE;
    }

    public static Availability availabilityOf(Combo combo, String whatSubstring) {
        for (Capability c : capabilitiesOf(combo)) {
            if (c.what().contains(whatSubstring)) {
                return c.availability();
            }
        }
        return Availability.UNVERIFIED;
    }

    private static final String PACK_ENUMERATION_WHAT = "资源包";

    public static Availability packEnumerationAvailability(Combo combo) {
        return availabilityOf(combo, PACK_ENUMERATION_WHAT);
    }

    public static Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<Combo, List<Capability>> e : TABLE.entrySet()) {
            List<Object> rows = new java.util.ArrayList<>();
            for (Capability c : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("what", c.what());
                m.put("availability", c.availability().name());
                m.put("evidence", c.evidence());
                m.put("notes", c.notes());
                rows.add(m);
            }
            out.put(e.getKey().toString(), rows);
        }
        return out;
    }

    public static long verifiedCount() {
        return TABLE.values().stream().flatMap(List::stream)
                .filter(c -> c.availability() == Availability.VERIFIED).count();
    }
}
