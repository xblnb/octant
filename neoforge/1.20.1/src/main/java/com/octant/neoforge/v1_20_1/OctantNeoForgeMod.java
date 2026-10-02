package com.octant.neoforge.v1_20_1;

import com.octant.common.platforms.PlatformAdapter;
import com.octant.common.platforms.PlatformCapabilities;
import com.octant.common.model.CaptureEvent;
import com.octant.pipeline.adapter.CaptureEventAdapter;
import com.octant.pipeline.analysis.AnalysisReport;
import com.octant.pipeline.export.ExportPipeline;
import com.octant.pipeline.raw.ContentCatalog;
import com.octant.pipeline.raw.RawEvent;
import com.octant.pipeline.raw.UnitKind;

import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.octant.capture.CaptureRuntime;
import com.octant.capture.CombatEncounters;
import com.octant.capture.ModSelfCheck;
import com.octant.capture.OctantHost;
import com.octant.capture.PrivacyConsent;
import com.octant.capture.ObservationAdapter;

@Mod(OctantNeoForgeMod.MOD_ID)
public final class OctantNeoForgeMod {

    public static final String MOD_ID = "octant";

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger("octant");

    private static volatile CaptureRuntime runtime;

    private static volatile Boolean startupSelfCheckOk;

    private final IEventBus modBus;
    private final IEventBus forgeBus;

    public OctantNeoForgeMod() {
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            net.minecraftforge.fml.ModLoadingContext.get().registerExtensionPoint(
                    net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory(
                            (mc, parent) -> new com.octant.neoforge.v1_20_1.client.OctantScreen(parent)));
        }
        this.modBus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
                .get().getModEventBus();
        this.forgeBus = net.minecraftforge.common.MinecraftForge.EVENT_BUS;

        forgeBus.register(this);

        LOG.info("[Octant（卦限）] 入口已构造：modId=" + MOD_ID
                + " version=" + modVersion()
                + " forge=" + forgeVersion());
        runContractSelfCheck();

        runStartupEngineSelfCheck();

        String autotestDir = System.getProperty("octant.autotest.export");
        if (autotestDir != null && !autotestDir.isBlank()) {
            runExportAutotest(Path.of(autotestDir),
                    ModSelfCheck.syntheticEvents());
        }
    }

    public static CaptureRuntime runtimeOrNull() {
        return runtime;
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        try {
            MinecraftServer server = event.getServer();
            OctantHost host = new ForgeModHost(server, modVersion(), gameVersion());
            runtime = new CaptureRuntime(host);
            LOG.info("[Octant（卦限）] 采集运行时就绪：worldDir=" + host.worldDir()
                    + " 同意文件=" + runtime.consent().privacyFile()
                    + " 采集=" + (runtime.collecting() ? "开启" : "关闭（默认，fail-closed）"));
        } catch (RuntimeException ex) {
            runtime = null;
            LOG.log(java.util.logging.Level.SEVERE, "[Octant（卦限）] 采集运行时初始化失败，本次会话不采集", ex);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        CaptureRuntime rt = runtime;
        if (rt == null) {
            return;
        }
        try {
            for (UUID id : onlinePlayerIds(event.getServer())) {
                rt.onPlayerLeave(id);
            }
            rt.store().saveTruncationLedger();
        } catch (Exception ex) {
            LOG.log(java.util.logging.Level.WARNING, "[Octant（卦限）] 收尾时出错（已吞掉，不影响退出）", ex);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        OctantCommands.register(event.getDispatcher());
        LOG.info("[Octant（卦限）] 命令已注册：/octant status|grant|revoke|export|verify");
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        CaptureRuntime rt = runtime;
        Player player = event.getEntity();
        if (rt == null || player == null) {
            return;
        }
        CaptureRuntime.PlayerSession s = rt.onPlayerJoin(player.getUUID());
        LOG.info("[Octant（卦限）] 会话开启：" + s.sessionId() + "（采集="
                + (rt.collecting() ? "开启" : "关闭") + "）");
        if (player instanceof ServerPlayer sp) {
            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[Octant（卦限）] 默认不采集。执行 /octant status 查看状态，"
                            + "/octant grant 明确授权（随时可用 /octant revoke 撤回）。"));
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        CaptureRuntime rt = runtime;
        Player player = event.getEntity();
        if (rt == null || player == null) {
            return;
        }
        rt.onPlayerLeave(player.getUUID());
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        CaptureRuntime rt = runtime;
        Player player = event.getEntity();
        if (rt == null || player == null) {
            return;
        }
        rt.noteWorldChanged(player.getUUID());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        CaptureRuntime rt = runtime;
        if (rt == null || event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
            rt.onServerTick(p.getUUID());
            observeBiome(rt, p);
        }
    }

    @SubscribeEvent
    public void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        CaptureRuntime rt = runtime;
        Player player = event.getEntity();
        if (rt == null || player == null) {
            return;
        }
        Advancement a = event.getAdvancement();
        if (a == null || a.getId() == null) {
            return;
        }
        ResourceLocation id = a.getId();
        ResourceLocation parent = a.getParent() == null ? null : a.getParent().getId();
        rt.onAdvancement(player.getUUID(), id.toString(),
                parent == null ? "" : parent.toString(),
                false, false);
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        CaptureRuntime rt = runtime;
        LivingEntity entity = event.getEntity();
        if (rt == null || entity == null) {
            return;
        }
        if (!(entity instanceof ServerPlayer player)) {
            MinecraftServer server = entity.getServer();
            if (server == null) {
                return;
            }
            String targetId = entity.getUUID().toString();
            for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
                rt.onOpponentDeath(p.getUUID(), targetId);
            }
            return;
        }
        DamageSource src = event.getSource();
        String cause = src == null || src.getMsgId() == null ? "unknown" : src.getMsgId();
        String killer = "";
        Entity attacker = src == null ? null : src.getEntity();
        if (attacker != null) {
            ResourceLocation key =
                    net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(attacker.getType());
            killer = key == null ? "" : key.toString();
        }
        rt.onPlayerDeath(player.getUUID(), cause, causeClassOf(cause, attacker), killer, false);
    }

    @SubscribeEvent
    public void onContainerOpen(net.minecraftforge.event.entity.player.PlayerContainerEvent.Open event) {
        CaptureRuntime rt = runtime;
        if (rt == null || event.getEntity() == null || event.getContainer() == null) {
            return;
        }
        net.minecraft.world.inventory.AbstractContainerMenu menu = event.getContainer();
        net.minecraft.resources.ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.MENU_TYPES
                .getKey(menu.getType());
        if (key == null) {
            return;
        }
        java.util.TreeMap<String, Integer> byItem = new java.util.TreeMap<>();
        for (net.minecraft.world.item.ItemStack st : menu.getItems()) {
            if (st == null || st.isEmpty()) {
                continue;
            }
            net.minecraft.resources.ResourceLocation id =
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(st.getItem());
            if (id == null) {
                continue;
            }
            byItem.merge(id.toString(), st.getCount(), Integer::sum);
        }
        java.util.List<java.util.Map<String, Object>> digest = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Integer> en : byItem.entrySet()) {
            if (digest.size() >= MAX_CONTAINER_DIGEST_ITEMS) {
                break;
            }
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("item", en.getKey());
            row.put("count", en.getValue());
            digest.add(row);
        }
        rt.onContainerSnapshot(event.getEntity().getUUID(), key.toString(), digest,
                byItem.size() > MAX_CONTAINER_DIGEST_ITEMS);
    }

    private static final int MAX_CONTAINER_DIGEST_ITEMS = 32;

    @SubscribeEvent
    public void onItemCrafted(net.minecraftforge.event.entity.player.PlayerEvent.ItemCraftedEvent event) {
        CaptureRuntime rt = runtime;
        if (rt == null || event.getEntity() == null) {
            return;
        }
        noteItem(rt, event.getEntity(), event.getCrafting(), "craft_output");
    }

    @SubscribeEvent
    public void onItemPickup(net.minecraftforge.event.entity.player.PlayerEvent.ItemPickupEvent event) {
        CaptureRuntime rt = runtime;
        if (rt == null || event.getEntity() == null) {
            return;
        }
        noteItem(rt, event.getEntity(), event.getStack(), "obtain");
    }

    @SubscribeEvent
    public void onItemToss(net.minecraftforge.event.entity.item.ItemTossEvent event) {
        CaptureRuntime rt = runtime;
        if (rt == null || event.getPlayer() == null || event.getEntity() == null) {
            return;
        }
        noteItem(rt, event.getPlayer(), event.getEntity().getItem(), "drop");
    }

    private static void noteItem(CaptureRuntime rt, net.minecraft.world.entity.player.Player player,
                                 net.minecraft.world.item.ItemStack stack, String action) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        net.minecraft.resources.ResourceLocation id =
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return;
        }
        rt.onItemAction(player.getUUID(), id.toString(), action,
                Math.max(1, stack.getCount()), player.isCreative());
    }

    @SubscribeEvent
    public void onLivingHurt(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
        CaptureRuntime rt = runtime;
        if (rt == null) {
            return;
        }
        LivingEntity victim = event.getEntity();
        if (victim == null || victim.level().isClientSide()) {
            return;
        }
        DamageSource src = event.getSource();
        Entity attacker = src == null ? null : src.getEntity();
        Entity direct = src == null ? null : src.getDirectEntity();
        boolean ranged = direct != null && direct != attacker;
        double damage = event.getAmount();

        if (victim instanceof ServerPlayer victimPlayer) {
            if (attacker != null && !(attacker instanceof ServerPlayer)) {
                rt.onPlayerHurt(victimPlayer.getUUID(), attacker.getUUID().toString(), damage);
            }
            return;
        }
        if (attacker instanceof ServerPlayer attackingPlayer) {
            String type = entityTypeOf(victim);
            if (type == null) {
                return;
            }
            rt.onPlayerAttack(attackingPlayer.getUUID(), victim.getUUID().toString(),
                    type, threatOf(victim), damage, ranged);
            return;
        }
        if (attacker != null) {
            double contrib = attacker instanceof LivingEntity helper ? threatOf(helper) : 0.0d;
            rt.onForeignAttack(victim.getUUID().toString(), attacker.getUUID().toString(), contrib);
        }
    }

    private static double threatOf(LivingEntity e) {
        double attack;
        try {
            attack = e.getAttributeValue(
                    net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        } catch (RuntimeException ex) {
            attack = 0.0d;
        }
        return Math.max(0.0d, e.getMaxHealth()) * Math.max(0.0d, attack);
    }

    private static String entityTypeOf(LivingEntity e) {
        ResourceLocation key =
                net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
        return key == null ? null : key.toString();
    }

    private void observeBiome(CaptureRuntime rt, ServerPlayer p) {
        Level level = p.level();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        ResourceLocation biome = serverLevel.getBiome(p.blockPosition())
                .unwrapKey().map(k -> k.location()).orElse(null);
        if (biome == null) {
            return;
        }
        ResourceLocation dim = serverLevel.dimension().location();
        rt.onBiome(p.getUUID(), biome.toString(), dim.toString());
    }

    static String causeClassOf(String causeMsgId, Entity attacker) {
        String c = causeMsgId == null ? "" : causeMsgId.toLowerCase(java.util.Locale.ROOT);
        if (c.contains("fall")) {
            return "fall";
        }
        if (c.contains("lava")) {
            return "lava";
        }
        if (c.contains("starve")) {
            return "starvation";
        }
        if (c.contains("drown")) {
            return "drowning";
        }
        if (c.contains("player") || c.contains("arrow")) {
            return attacker != null && attacker.getType() != null
                    && attacker instanceof Player ? "player" : "mob";
        }
        if (attacker != null) {
            return attacker instanceof Player ? "player" : "mob";
        }
        if (c.contains("inFire") || c.contains("onFire") || c.contains("cactus")
                || c.contains("outOfWorld") || c.contains("lightning") || c.contains("inWall")
                || c.contains("magic") || c.contains("wither") || c.contains("fallingBlock")
                || c.contains("freeze") || c.contains("sweetBerry")) {
            return "environment";
        }
        return "unknown";
    }

    private static List<UUID> onlinePlayerIds(MinecraftServer server) {
        List<UUID> out = new ArrayList<>();
        if (server == null || server.getPlayerList() == null) {
            return out;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            out.add(p.getUUID());
        }
        return out;
    }

    private void runContractSelfCheck() {
        try {
            PlatformAdapter.requireConsistent(
                    com.octant.common.model.CaptureEventType.SESSION_START,
                    new java.util.LinkedHashMap<>(java.util.Map.of(
                            "privacyClass", "singleplayer",
                            "gameVersion", "1.20.1",
                            "loader", "forge",
                            "sessionStartDate", "1970-01-01",
                            "cheatsEnabled", Boolean.FALSE)));
            LOG.info("[Octant（卦限）] 契约自检通过：session_start 的 payload 键集合与 dc §2.4 一致");
        } catch (RuntimeException ex) {
            LOG.log(java.util.logging.Level.SEVERE, "[Octant（卦限）] 契约自检失败（字段表与契约不一致）", ex);
        }
        try {
            PlatformCapabilities.Combo combo = PlatformCapabilities.Combo.of(
                    PlatformCapabilities.Loader.FORGE, PlatformCapabilities.McVersion.V1_20_1);
            PlatformCapabilities.Availability pack =
                    PlatformCapabilities.packEnumerationAvailability(combo);
            LOG.info("[Octant（卦限）] 平台能力事实：组合=" + combo
                    + " 包构成枚举=" + pack
                    + " 已核实能力 " + PlatformCapabilities.verifiedCount() + " 条");
        } catch (RuntimeException ex) {
            LOG.log(java.util.logging.Level.FINE, "[Octant（卦限）] 平台能力事实读取失败（不影响采集）", ex);
        }
        LOG.info("[Octant（卦限）] 已接线事件类型 " + ObservationAdapter.wiredTypes().size() + " 种；"
                + "未接线 " + ObservationAdapter.unwiredTypes().size() + " 种（如实登记，不假装覆盖）");
    }

    private void runStartupEngineSelfCheck() {
        boolean fatal = Boolean.parseBoolean(System.getProperty("octant.selftest.fatal", "false"));
        try {
            OctantHost host = new HeadlessModHost();
            ModSelfCheck.Result r = ModSelfCheck.run(host);
            startupSelfCheckOk = r.ok();
            if (r.ok()) {
                LOG.info("[Octant（卦限）] 启动期引擎自检通过（mode=" + (fatal ? "fatal" : "warn") + "）");
            } else {
                LOG.severe("[Octant（卦限）] 启动期引擎自检未通过 —— 本 jar 的引擎打包不完整，"
                        + "导出/图表渲染会在使用时失败（模组本身仍会加载：" + (fatal ? "fatal 模式将中止" : "非 fatal 模式不中止") + "）");
            }
            for (String line : r.lines()) {
                LOG.info("[Octant（卦限）][selftest] " + line);
            }
            if (!r.ok() && fatal) {
                throw new IllegalStateException(
                        "[Octant（卦限）] 启动期引擎自检未通过（octant.selftest.fatal=true）："
                                + "引擎类或字体资产没有随 jar 打包完整");
            }
        } catch (RuntimeException ex) {
            startupSelfCheckOk = false;
            if (fatal && ex instanceof IllegalStateException) {
                throw ex;
            }
            LOG.log(java.util.logging.Level.SEVERE,
                    "[Octant（卦限）] 启动期引擎自检本身失败（不影响游戏启动）", ex);
        }
    }

    public static Boolean startupSelfCheckOk() {
        return startupSelfCheckOk;
    }

    public static Map<String, String> runExportAutotest(Path testGameDir, List<CaptureEvent> events) {
        Path consentFile = com.octant.common.privacy.OctantPaths.dataDir(testGameDir.resolve("config")).resolve("privacy.json");
        LOG.info("[Octant（卦限）][autotest] 导出自测开始：gameDir=" + testGameDir
                + " 同意文件=" + consentFile);
        if (!Files.isRegularFile(consentFile)) {
            LOG.warning("[Octant（卦限）][autotest] 未找到玩家同意文件 ⇒ 本自测不执行（fail-closed）");
            return Map.of("__not_run__", "consent-file-missing");
        }
        try {
            CaptureEventAdapter.Conversion conv = CaptureEventAdapter.toPipelineTolerant(events);
            List<RawEvent> pipelineEvents = conv.events();
            ContentCatalog catalog = new ContentCatalog("autotest");
            catalog.register(UnitKind.ADVANCEMENT, List.of("minecraft:story/root"), true);
            catalog.register(UnitKind.BIOME, List.of("minecraft:plains"), true);
            catalog.register(UnitKind.DIMENSION, List.of("minecraft:overworld"), true);
            AnalysisReport report = AnalysisReport.analyze(pipelineEvents, catalog, "forge");
            List<String> categories = List.of("C1", "C2", "C3", "C5", "C6", "C7", "C8");

            ExportPipeline.Input input = new ExportPipeline.Input(
                    report, () -> pipelineEvents, catalog,
                    modVersion(), gameVersion(), "forge",
                    "autotest-salt", categories, modsList());
            Path exportRoot = com.octant.common.privacy.OctantPaths.dataDir(testGameDir).resolve("exports");

            ExportPipeline.Result result = ExportPipeline.write(input, exportRoot, Instant.now(),
                    (long) pipelineEvents.size(), () -> {
                        String text = Files.readString(consentFile,
                                java.nio.charset.StandardCharsets.UTF_8);
                        var root = com.octant.pipeline.json.JsonReader.parseObject(text);
                        Object permitRaw = root.get(PrivacyConsent.PERMIT_BLOCK);
                        if (!(permitRaw instanceof com.octant.pipeline.json.Json.JsonObject permit)) {
                            throw new IllegalArgumentException("同意状态缺少 permit 块");
                        }
                        boolean enabled = Boolean.TRUE.equals(permit.get("enabled"));
                        boolean exportEnabled = Boolean.TRUE.equals(permit.get("exportEnabled"));
                        boolean acknowledged = Boolean.TRUE.equals(permit.get("acknowledged"));
                        List<String> cats = new ArrayList<>();
                        if (permit.get("consentedCategories")
                                instanceof com.octant.pipeline.json.Json.JsonArray arr) {
                            for (Object item : arr.items()) {
                                if (item instanceof String s) {
                                    cats.add(s);
                                }
                            }
                        }
                        return new ExportPipeline.ConsentState(enabled, exportEnabled,
                                acknowledged, cats);
                    });

            if (!result.ok()) {
                LOG.severe("[Octant（卦限）][autotest] 导出未产出：" + result.failureReason()
                        + " 门禁命中=" + result.gateViolations());
                return Map.of("__failed__", String.valueOf(result.failureReason()));
            }
            LOG.info("[Octant（卦限）][autotest] 导出完成：exportId=" + result.exportId()
                    + " dir=" + result.directory() + " 报告单元=" + result.reportUnits()
                    + " 事件=" + events.size() + "（接受 " + conv.acceptedCount()
                    + " / 拒绝 " + conv.rejectedCount() + "）");
            for (var e : new java.util.TreeMap<>(result.fileBytes()).entrySet()) {
                LOG.info(String.format("[Octant（卦限）][autotest]   产物 %-26s %9d B  sha256_16=%s",
                        e.getKey(), e.getValue(), result.fileSha256_16().get(e.getKey())));
            }
            return new java.util.TreeMap<>(result.fileSha256_16());
        } catch (Throwable ex) {
            LOG.log(java.util.logging.Level.SEVERE, "[Octant（卦限）][autotest] 导出自测异常", ex);
            return Map.of("__exception__", ex.getClass().getName() + ": " + ex.getMessage());
        }
    }

    static final class HeadlessModHost implements OctantHost {
        private final Path gameDir = Path.of(".").toAbsolutePath().normalize();
        private final Path worldDir = Path.of(System.getProperty("java.io.tmpdir", "."))
                .toAbsolutePath().normalize().resolve("octant-selftest");

        @Override
        public Path gameDir() {
            return gameDir;
        }

        @Override
        public Path worldDir() {
            return worldDir;
        }

        @Override
        public String modVersion() {
            return OctantNeoForgeMod.modVersion();
        }

        @Override
        public String gameVersion() {
            return OctantNeoForgeMod.gameVersion();
        }

        @Override
        public String loader() {
            return "forge";
        }

        @Override
        public List<String> modsList() {
            return OctantNeoForgeMod.modsList();
        }

        @Override
        public String privacyClass() {
            return "singleplayer";
        }

        @Override
        public boolean cheatsEnabled() {
            return false;
        }
    }

    public static String modVersion() {
        return containerVersion(MOD_ID);
    }

    public static String forgeVersion() {
        return containerVersion("forge");
    }

    public static String gameVersion() {
        String v = containerVersion("minecraft");
        return v == null || v.isBlank() ? "1.20.1" : v;
    }

    private static String containerVersion(String modId) {
        try {
            return ModList.get().getModContainerById(modId)
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("");
        } catch (RuntimeException ex) {
            return "";
        }
    }

    public static List<String> modsList() {
        List<String> out = new ArrayList<>();
        try {
            for (var info : new LinkedHashSet<>(ModList.get().getMods())) {
                out.add(info.getModId() + ":" + info.getVersion());
            }
        } catch (RuntimeException ex) {
            LOG.log(java.util.logging.Level.FINE, "[Octant（卦限）] 模组清单读取失败", ex);
        }
        return out;
    }

    public static final class ForgeModHost implements OctantHost {

        private final Path gameDir;
        private final Path worldDir;
        private final String modVersion;
        private final String gameVersion;
        private final boolean cheatsEnabled;

        ForgeModHost(MinecraftServer server, String modVersion, String gameVersion) {
            this.modVersion = modVersion;
            this.gameVersion = gameVersion;
            Path wd;
            try {
                wd = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .toAbsolutePath().normalize();
            } catch (RuntimeException ex) {
                wd = Path.of("world").toAbsolutePath().normalize();
            }
            this.worldDir = wd;
            Path parent = wd.getParent();
            this.gameDir = parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
            boolean cheats;
            try {
                cheats = server.getWorldData().getAllowCommands();
            } catch (RuntimeException ex) {
                cheats = false;
            }
            this.cheatsEnabled = cheats;
            try {
                Files.createDirectories(com.octant.common.privacy.OctantPaths.dataDir(worldDir));
            } catch (Exception ignored) {
            }
        }

        @Override
        public Path gameDir() {
            return gameDir;
        }

        @Override
        public Path worldDir() {
            return worldDir;
        }

        @Override
        public String modVersion() {
            return modVersion;
        }

        @Override
        public String gameVersion() {
            return gameVersion;
        }

        @Override
        public String loader() {
            return "forge";
        }

        @Override
        public List<String> modsList() {
            return OctantNeoForgeMod.modsList();
        }

        @Override
        public String privacyClass() {
            return "private_server";
        }

        @Override
        public boolean cheatsEnabled() {
            return cheatsEnabled;
        }
    }
}
