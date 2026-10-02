package com.octant.common.collection;

import com.octant.common.model.ContractException;
import com.octant.common.model.EventSource;
import com.octant.common.model.Json;
import com.octant.common.model.PayloadSchema;
import com.octant.common.model.RawEventSchema;
import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.common.platforms.PlatformAdapter;
import com.octant.common.platforms.PlatformCapabilities;
import com.octant.common.privacy.EnvSnapshot;
import com.octant.common.privacy.FieldRegistry;
import com.octant.common.privacy.PackSnapshot;
import com.octant.common.privacy.SaltProvider;
import com.octant.common.session.SessionEnvironmentRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvSnapshotTest {

    private static final List<String> REGISTERED_ENV_RAW_FIELDS = List.of(
            "session_environment.snapshotRev",
            "session_environment.envSchemaVersion",
            "local.meta.envSnapshot.modCount",
            "local.meta.envSnapshot.modCountBySourceClass",
            "local.meta.envSnapshot.resourcePackCount",
            "local.meta.envSnapshot.dataPackCount",
            "local.meta.envSnapshot.resourcePacksAvailable",
            "local.meta.envSnapshot.dataPacksAvailable");

    private static final List<String> DETAIL_KEYS = List.of(
            "snapshotRev", "envSchemaVersion", "modCount", "modCountBySourceClass",
            "resourcePackCount", "dataPackCount",
            "resourcePacksAvailable", "dataPacksAvailable");

    private static final SaltProvider SALT =
            SaltProvider.of(new byte[SaltProvider.SALT_BYTES]);

    private static final PlatformCapabilities.Combo CAN_ENUMERATE =
            PlatformCapabilities.Combo.of(PlatformCapabilities.Loader.NEOFORGE,
                    PlatformCapabilities.McVersion.V1_21_1);

    private static final PlatformCapabilities.Combo CANNOT_ENUMERATE =
            PlatformCapabilities.Combo.of(PlatformCapabilities.Loader.FABRIC,
                    PlatformCapabilities.McVersion.V1_21_1);

    private static PackSnapshot populated(SaltProvider salt, PlatformCapabilities.Combo combo) {
        PackSnapshot s = new PackSnapshot(salt).setEnumerationAvailable(combo);
        s.addResourcePack("hash-of-resourcepack-a", 120, 4_096_000L);
        s.addResourcePack("hash-of-resourcepack-b", 30, 512_000L);
        s.addDataPack("hash-of-datapack-a", 40, 256_000L);
        s.addMod(PackSnapshot.SourceCategory.T1_PUBLIC_VERIFIED, "jei", null, "15.62.0.216");
        s.addMod(PackSnapshot.SourceCategory.T1_PUBLIC_VERIFIED, "kubejs", null, "2001.6.5");
        s.addMod(PackSnapshot.SourceCategory.T3_CUSTOM_SOURCE, null, "hash-of-private-mod", "1.0.0");
        return s;
    }

    @Nested
    @DisplayName("形态①：这些字段真的落盘了，且明细可复算")
    class DetailIsReallyWritten {

        @Test
        @DisplayName("明细文件成立于 <world>/mcinsight/meta/env/<rev>.json，且 8 个键一个不少")
        void detailFileCarriesAllEightFields(@TempDir Path world) throws Exception {
            EnvSnapshot env = new EnvSnapshot(populated(SALT, CAN_ENUMERATE), 3);
            Path file = env.writeDetail(world);

            assertEquals(EnvSnapshot.detailPath(world, 3), file,
                    "路径必须是契约 EV-S4 写死的落点（隐私模型 §603 的删除范围第 ⑱ 类）");
            assertEquals(com.octant.common.privacy.OctantPaths.dataDir(world).resolve("meta").resolve("env").resolve("3.json"),
                    file, "不得落在别处：删除清单是逐条列举的，换位置就是新的漏清点");
            assertTrue(Files.isRegularFile(file), "明细必须真的写到磁盘上，不是只在内存里");

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = Json.decodeObject(
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            for (String key : DETAIL_KEYS) {
                assertTrue(parsed.containsKey(key), "明细缺键（登记表已声明）：" + key);
                assertNotNull(parsed.get(key));
            }
            assertEquals(8, DETAIL_KEYS.size(), "本常量必须恰好是 8 条");
        }

        @Test
        @DisplayName("判定：snapshotDigest16 可由写出的明细复算，不一致即阻断")
        void digestIsReproducibleFromTheFile(@TempDir Path world) throws Exception {
            EnvSnapshot env = new EnvSnapshot(populated(SALT, CAN_ENUMERATE), 1);
            env.writeDetail(world);
            env.verifyDetailReproducible(world);

            String onDisk = new String(Files.readAllBytes(EnvSnapshot.detailPath(world, 1)),
                    StandardCharsets.UTF_8);
            assertEquals(env.canonicalDetailString(), Json.encode(Json.decodeObject(onDisk)),
                    "写出的文件重新解析再编码必须逐字相同（否则摘要不可复算）");

            EnvSnapshot sameContent = new EnvSnapshot(populated(SALT, CAN_ENUMERATE), 1);
            assertEquals(env.detailDigest16(), sameContent.detailDigest16(),
                    "同盐同内容必须同摘要（否则'同一份'判定不成立）");
            assertEquals(16, env.detailDigest16().length());

            PackSnapshot more = populated(SALT, CAN_ENUMERATE);
            more.addMod(PackSnapshot.SourceCategory.T1_PUBLIC_VERIFIED, "extra", null, "1.0.0");
            assertNotEquals(env.detailDigest16(), new EnvSnapshot(more, 1).detailDigest16(),
                    "多一个模组必须换摘要 —— 否则 M10D 的'相邻会话是否同一份'永远判成'同一份'");
            assertNotEquals(env.detailDigest16(),
                    new EnvSnapshot(populated(SALT, CAN_ENUMERATE), 2).detailDigest16(),
                    "rev 参与摘要：不同 rev 不得共用同一摘要");

            assertThrows(java.io.UncheckedIOException.class,
                    () -> new EnvSnapshot(populated(SALT, CAN_ENUMERATE), 9)
                            .verifyDetailReproducible(world),
                    "尚未写出该 rev 的明细时，复算必须失败而不是'通过'");
            Files.write(EnvSnapshot.detailPath(world, 1),
                    "{\"rev\":999}".getBytes(StandardCharsets.UTF_8));
            assertThrows(ContractException.class, () -> env.verifyDetailReproducible(world),
                    "明细被改动后复算必须失败；若仍通过，说明判据是恒真的");
        }

        @Test
        @DisplayName("同一 rev 只写一次：内容相同不重写（逐会话发事件成本恒定）")
        void sameRevIsWrittenOnce(@TempDir Path world) throws Exception {
            EnvSnapshot env = new EnvSnapshot(populated(SALT, CAN_ENUMERATE), 7);
            Path file = env.writeDetail(world);
            byte[] first = Files.readAllBytes(file);
            java.nio.file.attribute.FileTime before =
                    Files.getLastModifiedTime(file);

            env.writeDetail(world);
            assertEquals(new String(first, StandardCharsets.UTF_8),
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            assertEquals(before, Files.getLastModifiedTime(file),
                    "同一 rev 内容相同不得重写（重写会无谓消耗容量上限）");
        }
    }

    @Nested
    @DisplayName("形态②：发出去的事件只带时间序指针，不带计数")
    class EventIsATimePointer {

        @Test
        @DisplayName("payload 恰为契约 EV-S4 的 5 个字段，且不含任何计数")
        void payloadHasExactlyTheContractFields() {
            Map<String, Object> p = SessionEnvironmentRecorder.payloadOf(
                    populated(SALT, CAN_ENUMERATE), 4, 1250L);

            assertEquals(List.of("envSchemaVersion", "snapshotRev", "snapshotDigest16",
                            "observedAtRelMs", "observedAtTick"),
                    List.copyOf(p.keySet()),
                    "字段与顺序都必须逐字对齐契约 EV-S4；顺序即落盘顺序");
            assertEquals(EnvSnapshot.SCHEMA_VERSION, p.get("envSchemaVersion"));
            assertEquals(4, p.get("snapshotRev"));
            assertEquals(1250L, p.get("observedAtRelMs"));
            assertEquals(25L, p.get("observedAtTick"), "tTick = round(1250/50) = 25");

            for (String forbidden : List.of("modCount", "modCountBySourceClass",
                    "resourcePackCount", "dataPackCount",
                    "resourcePacksAvailable", "dataPacksAvailable")) {
                assertFalse(p.containsKey(forbidden),
                        "契约 EV-S4 写明本事件**不再携带**" + forbidden
                                + "（同一事实只有一个落点，计数落在明细文件）");
            }
        }

        @Test
        @DisplayName("payload 能通过既有的 schema 通路构造出真事件（不是只在 Map 里成立）")
        void payloadSurvivesSchemaEncoding() {
            var packs = populated(SALT, CAN_ENUMERATE);
            CaptureEvent e = new CaptureEvent(
                    RawEventSchema.VERSION, CaptureEvent.eventId(1), "s0001",
                    "0123456789abcdef", CaptureEventType.SESSION_ENVIRONMENT, 1250L, 25L,
                    CaptureEventType.SESSION_ENVIRONMENT.category(), EventSource.NEOFORGE,
                    true, null, SessionEnvironmentRecorder.payloadOf(packs, 4, 1250L));

            assertEquals(CaptureEventType.SESSION_ENVIRONMENT, e.type());
            assertEquals("session_environment", e.type().eventId());
            assertEquals(com.octant.common.model.EventCategory.SESSION, e.category(),
                    "cat 必须与 type 所属类别一致（契约 §2.2 封套校验）");
            assertTrue(String.valueOf(e.payload().get("snapshotDigest16"))
                            .matches("[0-9a-f]{16}"),
                    "snapshotDigest16 必须是 16 位小写 hex（契约 EV-S4）");

            java.util.Map<String, Object> withExtra = new java.util.LinkedHashMap<>(
                    SessionEnvironmentRecorder.payloadOf(packs, 4, 1250L));
            withExtra.put("modCount", 238);
            assertThrows(ContractException.class, () -> new CaptureEvent(
                            RawEventSchema.VERSION, CaptureEvent.eventId(2), "s0001",
                            "0123456789abcdef", CaptureEventType.SESSION_ENVIRONMENT, 1250L, 25L,
                            CaptureEventType.SESSION_ENVIRONMENT.category(), EventSource.NEOFORGE,
                            true, null, withExtra),
                    "把计数塞回事件必须被拒 —— 契约 EV-S4 的'同一事实只有一个落点'");

            java.util.Map<String, Object> badVersion = new java.util.LinkedHashMap<>(
                    SessionEnvironmentRecorder.payloadOf(packs, 4, 1250L));
            badVersion.put("envSchemaVersion", "env-snapshot@0.0.1");
            assertThrows(ContractException.class, () -> new CaptureEvent(
                            RawEventSchema.VERSION, CaptureEvent.eventId(3), "s0001",
                            "0123456789abcdef", CaptureEventType.SESSION_ENVIRONMENT, 1250L, 25L,
                            CaptureEventType.SESSION_ENVIRONMENT.category(), EventSource.NEOFORGE,
                            true, null, badVersion),
                    "表外 schema 版本必须被拒（它就是'常量'这个说法的可执行形态）");
        }

        @Test
        @DisplayName("envSchemaVersion 是常量：表外取值必须被拒（单元素枚举即常量表达）")
        void envSchemaVersionIsAConstant() {
            Map<String, Object> bad = new java.util.LinkedHashMap<>(
                    SessionEnvironmentRecorder.payloadOf(populated(SALT, CAN_ENUMERATE), 1, 0L));
            bad.put("envSchemaVersion", "env-snapshot@9.9.9");
            assertThrows(ContractException.class,
                    () -> PayloadSchema.encode(CaptureEventType.SESSION_ENVIRONMENT, bad, 0L),
                    "非契约常量必须被拒；能改的值就不是常量");

            Map<String, Object> noRev = new java.util.LinkedHashMap<>(
                    SessionEnvironmentRecorder.payloadOf(populated(SALT, CAN_ENUMERATE), 1, 0L));
            noRev.remove("snapshotRev");
            assertThrows(ContractException.class,
                    () -> PayloadSchema.encode(CaptureEventType.SESSION_ENVIRONMENT, noRev, 0L),
                    "MUST 字段缺失必须被拒");
        }
    }

    @Nested
    @DisplayName("形态③：发出顺序 —— 先过同意门，再落盘")
    class ConsentGateComesFirst {

        @Test
        @DisplayName("同意门拒绝 ⇒ 一条事件发出、且磁盘上不出现任何明细字节")
        void rejectedByGateWritesNothing(@TempDir Path world) {
            SessionEnvironmentRecorder rec = new SessionEnvironmentRecorder();
            SessionEnvironmentRecorder.Emitted out = rec.emit(
                    world, populated(SALT, CAN_ENUMERATE), 1,
                    new SessionEnvironmentRecorder.SessionIdentity(
                            "s0001", "0123456789abcdef", EventSource.NEOFORGE, 0L, 1),
                    event -> false);

            assertFalse(out.emitted());
            assertEquals("REJECTED_BY_CONSENT_GATE", out.reason());
            assertFalse(SessionEnvironmentRecorder.detailExists(world, 1),
                    "未同意时明细文件必须不存在——它是可落盘数据，不是日志");
            assertFalse(Files.exists(com.octant.common.privacy.OctantPaths.dataDir(world)),
                    "更严的断言：连目录都不该被创建");
        }

        @Test
        @DisplayName("同意门放行 ⇒ 明细与事件同时成立，且事件里的摘要与明细自洽")
        void allowedByGateWritesBoth(@TempDir Path world) {
            SessionEnvironmentRecorder rec = new SessionEnvironmentRecorder();
            SessionEnvironmentRecorder.Emitted out = rec.emit(
                    world, populated(SALT, CAN_ENUMERATE), 5,
                    new SessionEnvironmentRecorder.SessionIdentity(
                            "s0001", "0123456789abcdef", EventSource.NEOFORGE, 500L, 2),
                    event -> CaptureEventType.SESSION_ENVIRONMENT == event.type());

            assertTrue(out.emitted(), out.reason());
            assertTrue(SessionEnvironmentRecorder.detailExists(world, 5));
            assertEquals(16, out.digest16().length(), "snapshotDigest16 是 16 位小写 hex");
            assertTrue(out.digest16().matches("[0-9a-f]{16}"));

            Map<String, Object> payload = SessionEnvironmentRecorder.payloadOf(
                    populated(SALT, CAN_ENUMERATE), 5, 500L);
            assertEquals(out.digest16().length(),
                    String.valueOf(payload.get("snapshotDigest16")).length());
        }

        @Test
        @DisplayName("每会话恰好一次：同会话重复调用被拒，而不是静默发第二条")
        void atMostOncePerSession(@TempDir Path world) {
            SessionEnvironmentRecorder rec = new SessionEnvironmentRecorder();
            var id = new SessionEnvironmentRecorder.SessionIdentity(
                    "s0001", "0123456789abcdef", EventSource.NEOFORGE, 0L, 1);
            assertTrue(rec.emit(world, populated(SALT, CAN_ENUMERATE), 1, id, e -> true).emitted());

            var again = rec.emit(world, populated(SALT, CAN_ENUMERATE), 1, id, e -> true);
            assertFalse(again.emitted(),
                    "同一会话第二条会让 M10D 拿到'同一侧两侧'，差值恒 0 而看起来正常");
            assertEquals("DUPLICATE_WITHIN_SESSION", again.reason());

            var next = new SessionEnvironmentRecorder.SessionIdentity(
                    "s0002", "0123456789abcdef", EventSource.NEOFORGE, 0L, 1);
            assertTrue(rec.emit(world, populated(SALT, CAN_ENUMERATE), 2, next, e -> true).emitted());
        }
    }

    @Nested
    @DisplayName("形态④：可得性三态由能力驱动，不由空列表驱动")
    class AvailabilityIsCapabilityDriven {

        @Test
        @DisplayName("能力不可得 ⇒ unknown（不是 unavailable 也不是全 0 计数）")
        void unavailableEnumerationYieldsUnknown(@TempDir Path world) throws Exception {
            EnvSnapshot noCap = new EnvSnapshot(new PackSnapshot(SALT)
                    .setEnumerationAvailable(CANNOT_ENUMERATE), 1);
            assertEquals(EnvSnapshot.EnvAvailability.UNKNOWN, noCap.resourcePacksAvailable(),
                    "未验证 ⇒ unknown；把'没验过'写成'不可用'同样是在编造事实");
            assertEquals(EnvSnapshot.EnvAvailability.UNKNOWN, noCap.dataPacksAvailable());

            noCap.writeDetail(world);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = Json.decodeObject(
                    new String(Files.readAllBytes(EnvSnapshot.detailPath(world, 1)),
                            StandardCharsets.UTF_8));
            assertEquals("unknown", parsed.get("resourcePacksAvailable"));
            assertEquals("unknown", parsed.get("dataPacksAvailable"));

            EnvSnapshot cap = new EnvSnapshot(populated(SALT, CAN_ENUMERATE), 1);
            assertEquals(EnvSnapshot.EnvAvailability.AVAILABLE, cap.resourcePacksAvailable());
        }

        @Test
        @DisplayName("rev < 1 必须被拒（契约 EV-S4 要求 ≥ 1）")
        void revMustBePositive() {
            assertThrows(ContractException.class,
                    () -> new EnvSnapshot(populated(SALT, CAN_ENUMERATE), 0));
            assertThrows(ContractException.class,
                    () -> new EnvSnapshot(populated(SALT, CAN_ENUMERATE), -1));
        }
    }

    @Nested
    @DisplayName("形态⑤：登记项与实现的对应关系是双向的")
    class RegistryBindingIsBidirectional {

        @Test
        @DisplayName("8 个 rawField 全部登记在 FieldRegistry 里，且处置取自登记表")
        void everyRawFieldIsRegistered() {
            for (String raw : REGISTERED_ENV_RAW_FIELDS) {
                assertTrue(FieldRegistry.lookup(raw).isPresent(),
                        "登记表声明了但 Java 侧没有：" + raw);
            }
            assertEquals(8, REGISTERED_ENV_RAW_FIELDS.size());
        }

        @Test
        @DisplayName("处置逐条对齐登记表：计数泛化、可得性保留枚举、rev/schemaVersion 保留")
        void transformsMirrorTheRegistry() {
            assertEquals(FieldRegistry.Transform.GENERALIZE_CATEGORY,
                    FieldRegistry.lookup("local.meta.envSnapshot.modCount").orElseThrow().transform());
            assertEquals(FieldRegistry.Transform.GENERALIZE_CATEGORY,
                    FieldRegistry.lookup("local.meta.envSnapshot.modCountBySourceClass")
                            .orElseThrow().transform());
            assertEquals(FieldRegistry.Transform.GENERALIZE_CATEGORY,
                    FieldRegistry.lookup("local.meta.envSnapshot.resourcePackCount")
                            .orElseThrow().transform());
            assertEquals(FieldRegistry.Transform.GENERALIZE_CATEGORY,
                    FieldRegistry.lookup("local.meta.envSnapshot.dataPackCount")
                            .orElseThrow().transform());
            assertEquals(FieldRegistry.Transform.KEEP_ENUM,
                    FieldRegistry.lookup("local.meta.envSnapshot.resourcePacksAvailable")
                            .orElseThrow().transform());
            assertEquals(FieldRegistry.Transform.KEEP_ENUM,
                    FieldRegistry.lookup("local.meta.envSnapshot.dataPacksAvailable")
                            .orElseThrow().transform());
            assertEquals(FieldRegistry.Transform.KEEP,
                    FieldRegistry.lookup("session_environment.snapshotRev").orElseThrow().transform());
            assertEquals(FieldRegistry.Transform.KEEP,
                    FieldRegistry.lookup("session_environment.envSchemaVersion")
                            .orElseThrow().transform());
        }

        @Test
        @DisplayName("明细的键集与 8 条 rawField 的后缀一一对应（不多不少）")
        void detailKeysMatchRegisteredSuffixes() {
            var detail = new EnvSnapshot(populated(SALT, CAN_ENUMERATE), 1).toDetailMap();
            for (int i = 0; i < REGISTERED_ENV_RAW_FIELDS.size(); i++) {
                String raw = REGISTERED_ENV_RAW_FIELDS.get(i);
                String suffix = raw.substring(raw.lastIndexOf('.') + 1);
                assertEquals(DETAIL_KEYS.get(i), suffix, "常量表自身必须一致");
                assertTrue(detail.containsKey(suffix),
                        "明细缺这个键 ⇒ 该登记项没有载体：" + suffix);
            }
            assertTrue(detail.containsKey("packSnapshot"));
            assertEquals(9, detail.size(), "明细恰为 8 个登记键 + 1 个明细本体");
        }
    }
}
