package com.octant.neoforge.v1_20_1;

import com.octant.common.privacy.adapter.ConsentState;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.octant.capture.CaptureRuntime;
import com.octant.capture.ModExportRunner;
import com.octant.capture.ModSelfCheck;
import com.octant.capture.OctantHost;

public final class OctantCommands {

    private static final Logger LOG = Logger.getLogger("octant");

    private static final Set<String> ALL_CATEGORIES = Set.copyOf(ConsentState.CATEGORY_IDS);

    private OctantCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("octant")
                .then(Commands.literal("status")
                        .executes(ctx -> runStatus(ctx.getSource())))
                .then(Commands.literal("grant")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> runGrant(ctx.getSource())))
                .then(Commands.literal("revoke")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> runRevoke(ctx.getSource())))
                .then(Commands.literal("export")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> runExport(ctx.getSource())))
                .then(Commands.literal("verify")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> runVerify(ctx.getSource())));
        dispatcher.register(root);
    }

    private static String exportsDirOf(CommandSourceStack src) {
        OctantHost host = hostOf(src);
        return host == null ? "（无法判定宿主目录）"
                : com.octant.common.privacy.OctantPaths.dataDir(host.gameDir()).resolve("exports").toString();
    }

    private static int runStatus(CommandSourceStack src) {
        CaptureRuntime rt = OctantNeoForgeMod.runtimeOrNull();
        if (rt == null) {
            reply(src, "§e[Octant（卦限）] 尚未进入世界，采集运行时未就绪。");
            return 0;
        }
        Path privacy = rt.consent().privacyFile();
        Map<String, Object> s = rt.statusMap();
        reply(src, "§6[Octant（卦限）] 当前状态");
        reply(src, "  采集：" + (Boolean.TRUE.equals(s.get("collecting"))
                ? "§a开启" : "§c关闭（默认）"));
        reply(src, "  已授权类别：" + s.get("categories"));
        reply(src, "  已写入事件：" + s.get("eventsWritten")
                + "　因同意门丢弃：" + s.get("droppedByConsent")
                + "　翻译失败：" + s.get("translationFailures"));
        reply(src, "  写盘被拒：" + s.get("writeRefused") + "　写盘失败：" + s.get("writeFailed")
                + "　丢弃合计：" + s.get("droppedTotal")
                + "（这四项相加 == 本次会话的缺号数，可用 /octant verify 对账）");
        if (s.get("stampRaised") instanceof Number n && n.longValue() > 0) {
            reply(src, "§e  §e时间戳被单调化抬高：" + n.longValue()
                    + " 条（事件没丢；这些条的时刻不是真实时刻，区间长度以 durMs 为准）");
        }
        Object lwf = s.get("lastWriteFailure");
        if (lwf != null) {
            reply(src, "§e  最近一次写盘失败原因：" + lwf);
        }
        reply(src, "  已接线事件类型：" + s.get("wiredTypes")
                + " 种；未接线：" + s.get("unwiredTypes") + " 种");
        reply(src, "  同意文件：" + privacy);
        reply(src, "  事件目录：" + rt.store().eventsRoot());
        reply(src, "  产物目录：" + exportsDirOf(src));
        return 1;
    }

    private static int runGrant(CommandSourceStack src) {
        CaptureRuntime rt = runtimeOf(src);
        if (rt == null) {
            return 0;
        }
        try {
            String instanceId = "pi_" + rt.consent().privacyFile().toString().hashCode();
            ConsentState state = rt.consent().grant(instanceId, ALL_CATEGORIES, true);
            reply(src, "§a[Octant（卦限）] 已授权采集。生效类别：" + state.collectionCategories());
            reply(src, "  §7本模组只写本机磁盘，运行期不联网；产物在 "
                    + exportsDirOf(src));
            reply(src, "  §7随时可用 §f/octant revoke§7 撤回；撤回立即停止采集，"
                    + "但**不追溯**已经分享出去的副本。");
            reply(src, "  §7导出必须由你再执行一次 §f/octant export§7，本模组不会自动导出。");
            return 1;
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "[Octant（卦限）] 授予同意失败", ex);
            reply(src, "§c[Octant（卦限）] 授予失败：" + ex.getMessage() + "（状态未改变）");
            return 0;
        }
    }

    private static int runRevoke(CommandSourceStack src) {
        CaptureRuntime rt = runtimeOf(src);
        if (rt == null) {
            return 0;
        }
        try {
            rt.consent().revoke();
            reply(src, "§e[Octant（卦限）] 已撤回同意：采集立即停止（同一次会话内生效）。");
            reply(src, "  §7已采集的本地数据仍在磁盘上，可用 §f/octant status§7 查看路径后自行删除；");
            reply(src, "  §7撤回**不追溯**此前已分享出去的副本。");
            return 1;
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "[Octant（卦限）] 撤回失败", ex);
            reply(src, "§c[Octant（卦限）] 撤回失败：" + ex.getMessage());
            return 0;
        }
    }

    private static int runExport(CommandSourceStack src) {
        CaptureRuntime rt = runtimeOf(src);
        if (rt == null) {
            return 0;
        }
        OctantHost host = hostOf(src);
        if (host == null) {
            reply(src, "§c[Octant（卦限）] 无法判定宿主目录，导出中止。");
            return 0;
        }
        reply(src, "§7[Octant（卦限）] 已开始导出：**后台进行**，游戏可以继续玩（进度会一条条回在下面）…");
        java.util.function.Consumer<String> progress = msg ->
                src.getServer().execute(() -> reply(src, "§7  " + msg));
        final CaptureRuntime rtF = rt;
        final OctantHost hostF = host;
        Thread worker = new Thread(() -> {
            try {
                ModExportRunner.Summary s = ModExportRunner.run(rtF, hostF, Instant.now(), progress);
                src.getServer().execute(() -> reportExportResult(src, s));
            } catch (Throwable ex) {
                src.getServer().execute(() -> reply(src,
                        "§c[Octant（卦限）] 导出异常已吞掉（不影响游戏）："
                                + ex.getClass().getSimpleName() + " " + ex.getMessage()));
            }
        }, "octant-export");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    private static void reportExportResult(CommandSourceStack src, ModExportRunner.Summary s) {
        {
            if (!s.ok()) {
                reply(src, "§c[Octant（卦限）] 导出未产出任何文件：" + s.failureReason());
                for (String v : s.gateViolations()) {
                    reply(src, "  §c门禁命中：" + v);
                }
                return;
            }
            reply(src, "§a[Octant（卦限）] 导出完成：§f" + s.exportId());
            reply(src, "  目录：" + s.directory());
            reply(src, "  采集事件 " + s.captureEventCount() + " 条（转入分析 "
                    + s.acceptedEventCount() + "，被拒 " + s.rejectedEventCount()
                    + "）　会话 " + s.sessionCount() + " 个");
            for (String line : ModExportRunner.describeFiles(s)) {
                reply(src, line);
            }
            reply(src, "  合计 " + s.totalBytes() + " 字节，PDF " + s.pdfPages() + " 页");
            return;
        }
    }

    private static int runVerify(CommandSourceStack src) {
        CaptureRuntime rt = runtimeOf(src);
        if (rt == null) {
            return 0;
        }
        OctantHost host = hostOf(src);
        if (host == null) {
            reply(src, "§c[Octant（卦限）] 无法判定宿主目录。");
            return 0;
        }
        ModSelfCheck.Result r = ModSelfCheck.run(host, rt);
        reply(src, r.ok() ? "§a[Octant（卦限）] 自检通过" : "§c[Octant（卦限）] 自检失败");
        for (String line : r.lines()) {
            reply(src, "  " + line);
        }
        return r.ok() ? 1 : 0;
    }

    private static CaptureRuntime runtimeOf(CommandSourceStack src) {
        CaptureRuntime rt = OctantNeoForgeMod.runtimeOrNull();
        if (rt == null) {
            reply(src, "§c[Octant（卦限）] 采集运行时未就绪（尚未进入世界）。");
            return null;
        }
        return rt;
    }

    private static OctantHost hostOf(CommandSourceStack src) {
        try {
            return new OctantNeoForgeMod.ForgeModHost(src.getServer(), OctantNeoForgeMod.modVersion(),
                    OctantNeoForgeMod.gameVersion());
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "[Octant（卦限）] 宿主目录判定失败", ex);
            return null;
        }
    }

    private static void reply(CommandSourceStack src, String text) {
        Component msg = Component.literal(text);
        src.sendSuccess(() -> msg, false);
        if (!(src.getEntity() instanceof ServerPlayer)) {
            LOG.info(text.replace("§", ""));
        }
    }

    public static Map<String, String> commandTable() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("status", "查看同意状态与采集计数（只读，权限 0）");
        m.put("grant", "明确授权采集 + 本机导出（权限 2）");
        m.put("revoke", "撤回同意，立即停止采集（权限 2）");
        m.put("export", "现在导出一次报告包（权限 2，唯一产出产物的入口）");
        m.put("verify", "无头自检：引擎与打包完整性（权限 2）");
        return m;
    }

    static CommandSourceStack sourceOf(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource();
    }
}
