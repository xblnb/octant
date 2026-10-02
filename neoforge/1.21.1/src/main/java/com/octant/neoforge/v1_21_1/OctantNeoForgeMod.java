package com.octant.neoforge.v1_21_1;

import com.octant.capture.CaptureRuntime;
import com.octant.capture.ModExportRunner;
import com.octant.capture.ModSelfCheck;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Mod(OctantNeoForgeMod.MOD_ID)
public final class OctantNeoForgeMod {

    public static final String MOD_ID = "octant";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static final String MC_VERSION = "1.21.1";
    private static final String INSTANCE_TAG = "neoforge-1.21.1";

    private static volatile CaptureRuntime runtime;
    private static volatile OctantNeoForgeHost host;
    private static final Set<UUID> online = new LinkedHashSet<>();

    public OctantNeoForgeMod() {
        NeoForge.EVENT_BUS.register(this);
        LOG.info("[Octant（卦限）] Forge 1.21.1 端已加载：事件总线已注册");
    }

    private static CaptureRuntime runtime() {
        CaptureRuntime r = runtime;
        if (r == null) {
            synchronized (OctantNeoForgeMod.class) {
                if (runtime == null) {
                    host = new OctantNeoForgeHost(modVersion(), MC_VERSION);
                    runtime = new CaptureRuntime(host);
                }
                r = runtime;
            }
        }
        return r;
    }

    public static String modVersion() {
        return net.neoforged.fml.ModList.get().getModContainerById(MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent e) {
        CaptureRuntime r = runtime();
        MinecraftServer server = e.getServer();
        OctantNeoForgeHost h = host;
        if (h != null) {
            h.worldDir(server.getWorldPath(LevelResource.ROOT));
            h.serverFacts(server.isSingleplayer() ? "singleplayer" : "unknown",
                    server.getWorldData().isAllowCommands());
        }
        r.consent().reload();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent e) {
        CaptureRuntime r = runtime;
        if (r == null) {
            return;
        }
        for (UUID id : new LinkedHashSet<>(online)) {
            r.onPlayerLeave(id);
        }
        online.clear();
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            online.add(p.getUUID());
            runtime().onPlayerJoin(p.getUUID());
            runtime().consent().reload();
        }
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            online.remove(p.getUUID());
            CaptureRuntime r = runtime;
            if (r != null) {
                r.onPlayerLeave(p.getUUID());
            }
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post e) {
        CaptureRuntime r = runtime;
        if (r == null || online.isEmpty()) {
            return;
        }
        for (UUID id : online) {
            r.onServerTick(id);
        }
    }

    @SubscribeEvent
    public void onIncomingDamage(LivingIncomingDamageEvent e) {
        CaptureRuntime r = runtime;
        if (r == null) {
            return;
        }
        LivingEntity victim = e.getEntity();
        Entity attacker = e.getSource().getDirectEntity();
        if (attacker instanceof ServerPlayer p) {
            String typeId = entityTypeId(victim);
            if (typeId != null) {
                r.onPlayerAttack(p.getUUID(), victim.getUUID().toString(), typeId,
                        threatOf(victim), e.getAmount(), e.getSource().getDirectEntity() != victim);
            }
        } else if (attacker != null) {
            r.onForeignAttack(victim.getUUID().toString(), attacker.getUUID().toString(),
                    threatOf(attacker));
        }
        if (victim instanceof ServerPlayer p) {
            Entity src = e.getSource().getEntity();
            r.onPlayerHurt(p.getUUID(), src == null ? "" : src.getUUID().toString(), e.getAmount());
        }
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent e) {
        CaptureRuntime r = runtime;
        if (r == null) {
            return;
        }
        LivingEntity dead = e.getEntity();
        if (dead instanceof ServerPlayer p) {
            Entity killer = e.getSource().getEntity();
            String killerType = entityTypeId(killer);
            r.onPlayerDeath(p.getUUID(), e.getSource().getMsgId(),
                    killer == null ? "" : killer.getClass().getSimpleName(),
                    killerType == null ? "" : killerType, false);
        } else if (e.getSource().getEntity() instanceof ServerPlayer p) {
            r.onOpponentDeath(p.getUUID(), dead.getUUID().toString());
        }
    }

    @SubscribeEvent
    public void onAdvancement(AdvancementEvent.AdvancementEarnEvent e) {
        CaptureRuntime r = runtime;
        if (r == null || !(e.getEntity() instanceof ServerPlayer p)) {
            return;
        }
        var holder = e.getAdvancement();
        if (holder == null || holder.id() == null) {
            return;
        }
        String id = holder.id().toString();
        String parent = holder.value() == null || holder.value().parent().isEmpty()
                ? "" : holder.value().parent().get().toString();
        r.onAdvancement(p.getUUID(), id, parent, false, false);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("octant")
                .then(Commands.literal("status").executes(OctantNeoForgeMod::cmdStatus))
                .then(Commands.literal("verify").executes(OctantNeoForgeMod::cmdVerify))
                .then(Commands.literal("grant").executes(ctx -> cmdConsent(ctx, true)))
                .then(Commands.literal("revoke").executes(ctx -> cmdConsent(ctx, false)))
                .then(Commands.literal("export").executes(OctantNeoForgeMod::cmdExport)));
    }

    private static int cmdStatus(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CaptureRuntime r = runtime();
        Map<String, Object> s = r.statusMap();
        reply(ctx.getSource(), "当前状态", true);
        reply(ctx.getSource(), "  采集：" + (r.collecting() ? "开启" : "关闭（默认不采集）"), true);
        reply(ctx.getSource(), "  同意文件：" + r.consent().privacyFile(), true);
        reply(ctx.getSource(), "  事件目录：" + ModSelfCheck.eventsRoot(r), true);
        reply(ctx.getSource(), "  已写入事件：" + s.get("eventsWritten")
                + "　因同意门丢弃：" + s.get("droppedByConsent")
                + "　翻译失败：" + s.get("translationFailures"), true);
        reply(ctx.getSource(), "  写盘被拒：" + s.get("writeRefused") + "　写盘失败：" + s.get("writeFailed")
                + "　丢弃合计：" + s.get("droppedTotal"), true);
        return 1;
    }

    private static int cmdVerify(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        ModSelfCheck.Result res = ModSelfCheck.run(host, runtime());
        reply(ctx.getSource(), res.ok() ? "自检通过" : "自检失败", res.ok());
        for (String line : res.lines()) {
            reply(ctx.getSource(), "  " + line, true);
        }
        return res.ok() ? 1 : 0;
    }

    private static int cmdConsent(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                                  boolean grant) {
        try {
            if (grant) {
                runtime().consent().grant(INSTANCE_TAG,
                        Set.of("C1", "C2", "C3", "C5", "C6", "C7", "C8"), true);
                reply(ctx.getSource(), "已授权采集；随时可用 /octant revoke 撤回", true);
            } else {
                runtime().consent().revoke();
                reply(ctx.getSource(), "已撤回授权；后续不再采集", true);
            }
            return 1;
        } catch (Exception ex) {
            reply(ctx.getSource(), "同意操作失败：" + ex.getClass().getSimpleName() + " " + ex.getMessage(),
                    false);
            return 0;
        }
    }

    private static int cmdExport(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        if (server == null) {
            reply(ctx.getSource(), "没有服务器实例 ⇒ 不导出", false);
            return 0;
        }
        server.execute(() -> {
            try {
                ModExportRunner.Summary s = ModExportRunner.run(runtime(), host, Instant.now());
                for (String line : ModExportRunner.describeFiles(s)) {
                    LOG.info(line);
                }
            } catch (Exception ex) {
                LOG.error("导出失败：{}", ex.toString());
            }
        });
        reply(ctx.getSource(), "已开始导出（完成后路径写进日志）", true);
        return 1;
    }

    private static String entityTypeId(Entity e) {
        if (e == null) {
            return null;
        }
        var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return key == null ? null : key.toString();
    }

    private static double threatOf(Entity e) {
        if (!(e instanceof LivingEntity le)) {
            return 0.0d;
        }
        double attack;
        try {
            attack = le.getAttributeValue(
                    net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        } catch (RuntimeException ex) {
            attack = 0.0d;
        }
        return Math.max(0.0d, le.getMaxHealth()) * Math.max(0.0d, attack);
    }

    private static void reply(CommandSourceStack src, String text, boolean ok) {
        src.sendSuccess(() -> Component.literal("[Octant（卦限）] " + text), false);
    }

    public static CaptureRuntime currentRuntime() {
        return runtime;
    }
}
