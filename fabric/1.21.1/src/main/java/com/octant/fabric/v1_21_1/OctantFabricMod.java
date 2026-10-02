package com.octant.fabric.v1_21_1;

import com.octant.capture.CaptureRuntime;
import com.octant.capture.ModExportRunner;
import com.octant.capture.ModSelfCheck;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class OctantFabricMod implements ModInitializer, DedicatedServerModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("octant");

    private static final String INSTANCE_TAG = "fabric-1.21.1";

    private static volatile CaptureRuntime runtime;
    private static volatile OctantFabricHost host;

    private static final Set<UUID> online = new LinkedHashSet<>();

    private static CaptureRuntime runtime() {
        CaptureRuntime r = runtime;
        if (r == null) {
            synchronized (OctantFabricMod.class) {
                if (runtime == null) {
                    host = new OctantFabricHost();
                    runtime = new CaptureRuntime(host);
                }
                r = runtime;
            }
        }
        return r;
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(OctantFabricMod::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(OctantFabricMod::onServerStopping);

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            CaptureRuntime r = runtime;
            if (r == null || online.isEmpty()) {
                return;
            }
            for (UUID id : online) {
                r.onServerTick(id);
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            UUID id = handler.getPlayer().getUUID();
            online.add(id);
            runtime().onPlayerJoin(id);
            runtime().consent().reload();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            online.remove(id);
            CaptureRuntime r = runtime;
            if (r != null) {
                r.onPlayerLeave(id);
            }
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            CaptureRuntime r = runtime;
            if (r == null) {
                return;
            }
            if (entity instanceof net.minecraft.server.level.ServerPlayer p) {
                String killer = entityTypeId(source.getEntity());
                r.onPlayerDeath(p.getUUID(), source.getMsgId(),
                        source.getEntity() == null ? "" : source.getEntity().getClass().getSimpleName(),
                        killer == null ? "" : killer, false);
            } else if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) {
                r.onOpponentDeath(p.getUUID(), entity.getUUID().toString());
            }
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            CaptureRuntime r = runtime;
            if (r == null) {
                return true;
            }
            net.minecraft.world.entity.Entity attacker = source.getEntity();
            if (attacker instanceof net.minecraft.server.level.ServerPlayer p) {
                boolean ranged = source.getDirectEntity() != attacker;
                String typeId = entityTypeId(entity);
                if (typeId != null) {
                    r.onPlayerAttack(p.getUUID(), entity.getUUID().toString(),
                            typeId, threatOf(entity), amount, ranged);
                }
            } else if (attacker != null) {
                r.onForeignAttack(entity.getUUID().toString(), attacker.getUUID().toString(),
                        threatOf(attacker));
            }
            return true;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("octant")
                        .then(Commands.literal("status").executes(OctantFabricMod::cmdStatus))
                        .then(Commands.literal("verify").executes(OctantFabricMod::cmdVerify))
                        .then(Commands.literal("grant").executes(ctx -> cmdConsent(ctx, true)))
                        .then(Commands.literal("revoke").executes(ctx -> cmdConsent(ctx, false)))
                        .then(Commands.literal("export").executes(OctantFabricMod::cmdExport))));
        LOG.info("Octant（卦限）Fabric 端已接线：事件订阅 + 命令注册完成");
    }

    @Override
    public void onInitializeServer() {
        onInitialize();
    }

    private static void onServerStarting(MinecraftServer server) {
        CaptureRuntime r = runtime();
        OctantFabricHost h = host;
        if (h != null) {
            h.worldDir(server.getWorldPath(LevelResource.ROOT));
            h.serverFacts(server.isSingleplayer() ? "singleplayer" : "unknown",
                    server.getWorldData().isAllowCommands());
        }
        r.consent().reload();
    }

    private static void onServerStopping(MinecraftServer server) {
        CaptureRuntime r = runtime;
        if (r == null) {
            return;
        }
        for (UUID id : new LinkedHashSet<>(online)) {
            r.onPlayerLeave(id);
        }
        online.clear();
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

    private static String entityTypeId(net.minecraft.world.entity.Entity e) {
        if (e == null) {
            return null;
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return key == null ? null : key.toString();
    }

    private static double threatOf(net.minecraft.world.entity.Entity e) {
        if (!(e instanceof net.minecraft.world.entity.LivingEntity le)) {
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
