package com.octant.common.privacy;

import com.octant.common.model.ContractException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class FieldRegistry {

    public enum LocationClass {
        A(2),
        B(1),
        C(0);

        private final int strictness;

        LocationClass(int strictness) {
            this.strictness = strictness;
        }

        public int strictness() {
            return strictness;
        }
    }

    public enum Transform {

        KEEP("保留", "pass", false),
        KEEP_ID_WHITELIST("保留-ID白名单", "whitelist", false),
        KEEP_READONLY_SETTING("保留设置只读", "pass", false),
        KEEP_ENUM("保留枚举", "pass", false),
        KEEP_FOR_LOCAL_USE("使用保留", "pass", false),
        DROP("丢弃", "drop", false),
        DROP_COUNT_METADATA("丢弃-计入元数据", "drop", false),
        HASH_SALT_TRUNC64("哈希加盐截断64", "pseudonymize", true),
        DROP_RELABEL_SEQUENCE("丢弃-重标记为序号", "relabel", false),
        GENERALIZE_CATEGORY("丢弃-泛化为类别", "generalize", false),
        GENERALIZE_RAM_BUCKET("丢弃-泛化为硬件桶", "generalize", false),
        GENERALIZE_OS_FAMILY("丢弃-泛化为OS族", "generalize", false),
        GENERALIZE_GPU_FAMILY("丢弃-泛化为GPU族", "generalize", false),
        QUANTIZE_512("量化512", "quantize", false),
        QUANTIZE_32("量化32", "quantize", false),
        QUANTIZE_512_OFFSET("量化512+随机偏移", "quantize+offset", false),
        QUANTIZE_OFFSET("量化+随机偏移", "quantize+offset", false),
        QUANTIZE_TRUNCATE("量化+截断", "quantize+truncate", false),
        TRUNCATE_LANG_SUBTAG("截断-去子标签", "truncate", false),
        DROP_KEEP_ID_ONLY("丢弃-仅保留ID", "drop", false),
        RELATIVIZE_COARSEN("相对化+粗化", "relative+coarsen", false),
        TRUNCATE_DATE_GRANULARITY("截断-日期粒度", "truncate", false),
        GENERALIZE_DAY_BUCKET("丢弃-泛化为时间段桶", "generalize", false),
        GENERALIZE_LENGTH_BUCKET("丢弃-泛化为长度桶", "generalize", false),
        HASH_SALT_RECORD("哈希加盐记录", "pseudonymize", true),
        GENERALIZE_REASON_CODE("泛化为原因码", "generalize", false);

        private final String specId;
        private final String kind;
        private final boolean irreversible;

        Transform(String specId, String kind, boolean irreversible) {
            this.specId = specId;
            this.kind = kind;
            this.irreversible = irreversible;
        }

        public String specId() {
            return specId;
        }

        public String kind() {
            return kind;
        }

        public boolean isIrreversible() {
            return irreversible;
        }

        public boolean isDrop() {
            return "drop".equals(kind);
        }

        public static Transform bySpecId(String specId) {
            for (Transform t : values()) {
                if (t.specId.equals(specId)) {
                    return t;
                }
            }
            throw new ContractException("未知变换 ID（登记表未定义）：" + specId);
        }

        public static Optional<Transform> find(String specId) {
            for (Transform t : values()) {
                if (t.specId.equals(specId)) {
                    return Optional.of(t);
                }
            }
            return Optional.empty();
        }
    }

    public record FieldPolicy(String rawField, String fieldId, LocationClass locationClass,
                              Transform transform, Set<String> acceptance) {

        public FieldPolicy {
            if (rawField == null || !rawField.contains(".")) {
                throw new ContractException("rawField 必须是 <eventType>.<fieldPath> 形态：" + rawField);
            }
            acceptance = Collections.unmodifiableSet(new LinkedHashSet<>(
                    acceptance == null ? Set.of() : acceptance));
        }

        public boolean mustNotBeCollected() {
            return locationClass == LocationClass.C;
        }

        public boolean requiresIrreversibleTransformAtCollection() {
            return locationClass == LocationClass.B && transform.isIrreversible();
        }
    }

    public enum ViolationReason {
        NOT_COLLECTED_CLASS_C,
        IRREVERSIBLE_TRANSFORM_MISSING,
        UNREGISTERED_FIELD,

        UNREGISTERED_FIELD_REDACTED
    }

    public record Violation(String eventType, String fieldPath, ViolationReason reason, String transformSpecId) {

        public String rawField() {
            return eventType + "." + fieldPath;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("blocked", true);
            m.put("reason", reason.name());
            m.put("fieldPath", fieldPath);
            if (transformSpecId != null) {
                m.put("transform", transformSpecId);
            }
            return m;
        }
    }

    private static final Set<String> DEPRECATED_FIELD_IDS = Set.of("position.precise_optin");

    private static final Map<String, FieldPolicy> BY_RAW_FIELD;
    private static final Map<String, FieldPolicy> BY_FIELD_ID;

    static {
        Map<String, FieldPolicy> byRaw = new LinkedHashMap<>();
        Map<String, FieldPolicy> byId = new LinkedHashMap<>();
        reg(byRaw, byId, "player.uuid", "session_start.playerKey", LocationClass.B, Transform.HASH_SALT_TRUNC64, "AN-ID-01", "AN-ID-02", "AN-ID-05");
        reg(byRaw, byId, "player.uuid_label_map", "local.session.playerName", LocationClass.C, Transform.DROP, "AN-ID-02");
        reg(byRaw, byId, "player.name", "local.session.playerName", LocationClass.B, Transform.DROP_RELABEL_SEQUENCE, "AN-ID-02", "AN-TXT-02");
        reg(byRaw, byId, "player.skin_texture", "not_collected.skin_texture", LocationClass.C, Transform.DROP, "AN-ID-03");
        reg(byRaw, byId, "player.cape", "not_collected.cape", LocationClass.C, Transform.DROP, "AN-ID-03");
        reg(byRaw, byId, "player.game_profile_id", "not_collected.profile_id", LocationClass.C, Transform.DROP, "AN-ID-01");
        reg(byRaw, byId, "player.local_uuid_hint", "local.config.exporterOwnPlayerId", LocationClass.C, Transform.KEEP_FOR_LOCAL_USE, "AN-ID-04", "AN-AGG-05");
        reg(byRaw, byId, "entity.uuid", "not_collected.entity_uuid", LocationClass.B, Transform.DROP, "AN-ID-01");
        reg(byRaw, byId, "entity.custom_name", "not_collected.entity_custom_name", LocationClass.C, Transform.DROP, "AN-TXT-01");
        reg(byRaw, byId, "world.identity", "local.meta.worldIdentity", LocationClass.C, Transform.DROP, "AN-ID-05");
        reg(byRaw, byId, "world.path", "local.meta.absolutePath", LocationClass.C, Transform.DROP, "AN-TXT-02", "AN-TXT-04");
        reg(byRaw, byId, "config.paths_all", "local.config.anyPath", LocationClass.B, Transform.DROP, "AN-TXT-02", "AN-GATE-01");
        reg(byRaw, byId, "server.address", "session_start.privacyClass", LocationClass.B, Transform.GENERALIZE_CATEGORY, "AN-LOC-04", "AN-GATE-01");
        reg(byRaw, byId, "server.motd", "not_collected.server_motd", LocationClass.C, Transform.DROP, "AN-TXT-01");
        reg(byRaw, byId, "client.ip", "not_collected.client_ip", LocationClass.C, Transform.DROP, "AN-LOC-04", "AN-GATE-01");
        reg(byRaw, byId, "client.hostname", "not_collected.client_hostname", LocationClass.C, Transform.DROP, "AN-TXT-03");
        reg(byRaw, byId, "client.lang", "not_collected.locale", LocationClass.C, Transform.DROP, "AN-ID-05", "AN-TXT-07");
        reg(byRaw, byId, "client.mods_installed", "local.meta.envSnapshot", LocationClass.A, Transform.KEEP, "AN-TXT-01", "AN-GATE-03");
        reg(byRaw, byId, "client.mod_hashes", "local.meta.envSnapshot", LocationClass.A, Transform.KEEP, "AN-GATE-03");
        reg(byRaw, byId, "hardware.ram", "local.meta.envSnapshot", LocationClass.B, Transform.GENERALIZE_RAM_BUCKET, "AN-ID-05");
        reg(byRaw, byId, "hardware.cpu", "not_collected.cpu", LocationClass.C, Transform.DROP, "AN-ID-05");
        reg(byRaw, byId, "hardware.gpu", "local.meta.envSnapshot", LocationClass.B, Transform.GENERALIZE_GPU_FAMILY, "AN-ID-05");
        reg(byRaw, byId, "hardware.os", "local.meta.envSnapshot", LocationClass.B, Transform.GENERALIZE_OS_FAMILY, "AN-ID-05");
        reg(byRaw, byId, "hardware.os_user", "not_collected.os_user", LocationClass.C, Transform.DROP, "AN-TXT-02");
        reg(byRaw, byId, "time.absolute", "local.session_ledger.startDate", LocationClass.B, Transform.RELATIVIZE_COARSEN, "AN-LOC-03", "AN-TXT-07");
        reg(byRaw, byId, "session.start_local_time", "session_start.sessionStartLocalBucket", LocationClass.B, Transform.GENERALIZE_DAY_BUCKET, "AN-AGG-04");
        reg(byRaw, byId, "event.timestamp", "envelope.tRelMs", LocationClass.B, Transform.RELATIVIZE_COARSEN, "AN-LOC-03");
        reg(byRaw, byId, "position.xz", "region_first_visit.regionKey", LocationClass.B, Transform.QUANTIZE_512_OFFSET, "AN-LOC-01", "AN-LOC-02", "AN-SHARE-01");
        reg(byRaw, byId, "position.y", "region_first_visit.regionKey", LocationClass.B, Transform.QUANTIZE_OFFSET, "AN-LOC-02");
        reg(byRaw, byId, "position.precise_optin", "region_first_visit.regionKey", LocationClass.C, Transform.DROP, "AN-LOC-01", "AN-SHARE-02");
        reg(byRaw, byId, "location.dimension", "dimension_entered.dimension", LocationClass.A, Transform.KEEP_ID_WHITELIST, "AN-TXT-01", "AN-TXT-03");
        reg(byRaw, byId, "location.biome", "biome_visited.biome", LocationClass.A, Transform.KEEP_ID_WHITELIST, "AN-TXT-01");
        reg(byRaw, byId, "location.structure_found", "structure_entered.structure", LocationClass.A, Transform.KEEP_ID_WHITELIST, "AN-TXT-01", "AN-SHARE-01");
        reg(byRaw, byId, "progression.advancement_id", "advancement_gained.advancementId", LocationClass.A, Transform.KEEP_ID_WHITELIST, "AN-TXT-01");
        reg(byRaw, byId, "progression.advancement_display", "not_collected.advancement_display", LocationClass.C, Transform.DROP, "AN-TXT-01");
        reg(byRaw, byId, "progression.recipe_id", "recipe_unlocked.recipeId", LocationClass.A, Transform.KEEP_ID_WHITELIST, "AN-TXT-01");
        reg(byRaw, byId, "combat.damage_type", "player_death.deathCause", LocationClass.A, Transform.KEEP_ID_WHITELIST, "AN-TXT-01");
        reg(byRaw, byId, "combat.entity_type", "combat_started.entityType", LocationClass.A, Transform.KEEP_ID_WHITELIST, "AN-TXT-01");
        reg(byRaw, byId, "combat.player_attacker", "combat_started.opponentKey", LocationClass.A, Transform.KEEP_ENUM, "AN-ID-02", "AN-TXT-07");
        reg(byRaw, byId, "combat.attacker_is_player", "combat_started.shared", LocationClass.B, Transform.GENERALIZE_CATEGORY, "AN-ID-02", "AN-TXT-07");
        reg(byRaw, byId, "combat.death_cause", "player_death.deathCause", LocationClass.A, Transform.KEEP_ID_WHITELIST, "AN-TXT-01");
        reg(byRaw, byId, "combat.threat_proxy", "combat_started.opponentThreat", LocationClass.A, Transform.KEEP, "AN-AGG-01");
        reg(byRaw, byId, "combat.farm_pattern", "combat_started.farmPattern", LocationClass.B, Transform.GENERALIZE_CATEGORY, "AN-ID-02", "AN-TXT-07");
        reg(byRaw, byId, "combat.start_duration", "combat_started.durMs", LocationClass.A, Transform.KEEP, "AN-AGG-01");
        reg(byRaw, byId, "combat.ended_opponent", "combat_ended.opponentKey", LocationClass.A, Transform.KEEP_ENUM, "AN-ID-02", "AN-TXT-07");
        reg(byRaw, byId, "combat.outcome", "combat_ended.outcome", LocationClass.A, Transform.KEEP_ENUM, "AN-AGG-01");
        reg(byRaw, byId, "combat.damage_dealt", "combat_ended.damageDealt", LocationClass.A, Transform.KEEP, "AN-AGG-01");
        reg(byRaw, byId, "combat.damage_taken", "combat_ended.damageTaken", LocationClass.A, Transform.KEEP, "AN-AGG-01");
        reg(byRaw, byId, "combat.last_hit_by_player", "combat_ended.lastHitByPlayer", LocationClass.A, Transform.KEEP_ENUM, "AN-AGG-01");
        reg(byRaw, byId, "combat.resolved", "combat_ended.resolved", LocationClass.A, Transform.KEEP_ENUM, "AN-AGG-01");
        reg(byRaw, byId, "combat.tactic", "combat_ended.tactic", LocationClass.A, Transform.KEEP_ENUM, "AN-AGG-01");
        reg(byRaw, byId, "combat.ended_duration", "combat_ended.durMs", LocationClass.A, Transform.KEEP, "AN-AGG-01");
        reg(byRaw, byId, "content.block_id", "machine_observed.machineBlock", LocationClass.A, Transform.KEEP_ID_WHITELIST, "AN-TXT-01");
        reg(byRaw, byId, "content.item_id", "item_action.item", LocationClass.A, Transform.KEEP_ID_WHITELIST, "AN-TXT-01");
        reg(byRaw, byId, "items.action", "item_action.action", LocationClass.A, Transform.KEEP_ENUM, "AN-AGG-01");
        reg(byRaw, byId, "items.count", "item_action.count", LocationClass.A, Transform.KEEP, "AN-AGG-01");
        reg(byRaw, byId, "items.creative_given", "item_action.creativeGiven", LocationClass.A, Transform.KEEP_ENUM, "AN-AGG-01");
        reg(byRaw, byId, "containers.key", "container_snapshot.containerKey", LocationClass.A, Transform.KEEP_ID_WHITELIST, "AN-TXT-01");
        reg(byRaw, byId, "containers.digest", "container_snapshot.itemsDigest", LocationClass.A, Transform.KEEP, "AN-AGG-01");
        reg(byRaw, byId, "containers.truncated", "container_snapshot.truncated", LocationClass.A, Transform.KEEP_ENUM, "AN-AGG-01");
        reg(byRaw, byId, "content.item_display_name", "not_collected.item_display_name", LocationClass.C, Transform.DROP, "AN-TXT-01");
        reg(byRaw, byId, "content.quest_id", "quest_completed.questId", LocationClass.A, Transform.KEEP_ID_WHITELIST, "AN-TXT-01");
        reg(byRaw, byId, "content.quest_title", "not_collected.quest_title", LocationClass.C, Transform.DROP, "AN-TXT-01");
        reg(byRaw, byId, "session.start_date", "session_start.sessionStartDate", LocationClass.B, Transform.RELATIVIZE_COARSEN, "AN-LOC-03", "AN-TXT-07");
        reg(byRaw, byId, "session.world_age_days", "session_start.worldCreateDay", LocationClass.B, Transform.RELATIVIZE_COARSEN, "AN-LOC-03", "AN-TXT-07");
        reg(byRaw, byId, "session.wall_time", "session_end.wallMs", LocationClass.A, Transform.KEEP, "AN-AGG-01");
        reg(byRaw, byId, "session.afk_reason", "session_end.afkReason", LocationClass.A, Transform.KEEP_ENUM, "AN-AGG-01");
        reg(byRaw, byId, "session.close_cause", "session_end.closeCause", LocationClass.A, Transform.KEEP_ENUM, "AN-AGG-01");
        reg(byRaw, byId, "session.recovered_close", "session_end.openSession", LocationClass.A, Transform.KEEP, "AN-AGG-01");
        reg(byRaw, byId, "session.input_events", "session_end.inputEvents", LocationClass.A, Transform.KEEP, "AN-AGG-01");
        reg(byRaw, byId, "session.total_events", "session_end.totalEvents", LocationClass.A, Transform.KEEP, "AN-AGG-01");
        reg(byRaw, byId, "client.loader", "session_start.loader", LocationClass.A, Transform.KEEP, "AN-AGG-01");
        reg(byRaw, byId, "session.load_ms", "session_start.loadMs", LocationClass.A, Transform.KEEP, "AN-AGG-01");
        reg(byRaw, byId, "session.cheats_enabled", "session_start.cheatsEnabled", LocationClass.A, Transform.KEEP_ENUM, "AN-AGG-01");
        reg(byRaw, byId, "session.duration", "session_end.activeMs", LocationClass.A, Transform.KEEP, "AN-AGG-01");
        reg(byRaw, byId, "session.afk", "session_end.afkMs", LocationClass.A, Transform.KEEP_ENUM, "AN-AGG-01");
        reg(byRaw, byId, "event.count", "local.events.count", LocationClass.A, Transform.KEEP, "AN-AGG-01");
        reg(byRaw, byId, "chat.text", "not_collected.chat_text", LocationClass.C, Transform.DROP_COUNT_METADATA, "AN-TXT-01", "AN-CON-04");
        reg(byRaw, byId, "chat.count", "not_collected.chat_metadata", LocationClass.B, Transform.DROP_COUNT_METADATA, "AN-TXT-01", "AN-SHARE-03");
        reg(byRaw, byId, "cmd.text", "not_collected.command_text", LocationClass.C, Transform.DROP, "AN-TXT-01");
        reg(byRaw, byId, "cmd.selector", "not_collected.command_selector", LocationClass.C, Transform.DROP, "AN-TXT-02");
        reg(byRaw, byId, "chat.book_text", "not_collected.book_text", LocationClass.C, Transform.DROP, "AN-TXT-01");
        reg(byRaw, byId, "chat.sign_text", "not_collected.sign_text", LocationClass.C, Transform.DROP, "AN-TXT-01");
        reg(byRaw, byId, "session.player_uuid_hash", "envelope.playerKey", LocationClass.B, Transform.HASH_SALT_TRUNC64, "AN-ID-01", "AN-ID-05");
        reg(byRaw, byId, "keybind.custom", "not_collected.keybind", LocationClass.C, Transform.DROP, "AN-TXT-01");
        reg(byRaw, byId, "client.game_version", "session_start.gameVersion", LocationClass.A, Transform.KEEP, "AN-AGG-01");
        reg(byRaw, byId, "client.locale_text", "not_collected.locale_resource", LocationClass.C, Transform.DROP, "AN-TXT-02");
        reg(byRaw, byId, "analysis.metrics", "analysis.metrics", LocationClass.A, Transform.KEEP, "AN-AGG-06");
        reg(byRaw, byId, "analysis.conclusions", "analysis.conclusions", LocationClass.A, Transform.KEEP, "AN-GATE-01", "AN-AGG-06");
        reg(byRaw, byId, "analysis.evidence_raw", "analysis.evidence", LocationClass.B, Transform.KEEP, "AN-SHARE-03");
        reg(byRaw, byId, "raw.raw_events", "local.events.rawJsonl", LocationClass.C, Transform.DROP, "AN-LOCAL-02", "AN-SHARE-03");
        reg(byRaw, byId, "raw.pseudonym_salt", "local.meta.saltBin", LocationClass.C, Transform.DROP, "AN-ID-01", "AN-DEL-03");
        reg(byRaw, byId, "raw.cross_export_salt", "local.config.crossExportSalt", LocationClass.C, Transform.DROP, "AN-DEL-03", "AN-DEL-07");
        reg(byRaw, byId, "raw.consent_ledger", "local.config.consentLedger", LocationClass.C, Transform.DROP, "AN-CON-01", "AN-DEL-05");
        reg(byRaw, byId, "raw.local_persistence_scope", "local.config.persistenceScope", LocationClass.C, Transform.DROP, "AN-DEL-01", "AN-DEL-02", "AN-DEL-07");
        reg(byRaw, byId, "local.purge_all_trigger", "local.config.purgeAll", LocationClass.C, Transform.DROP, "AN-DEL-01", "AN-DEL-02");
        reg(byRaw, byId, "raw.session_ledger", "local.state.sessionLedger", LocationClass.C, Transform.DROP, "AN-LOC-03", "AN-DEL-07");
        reg(byRaw, byId, "raw.truncation_ledger", "local.state.truncationLedger", LocationClass.C, Transform.DROP, "AN-DEL-07");
        reg(byRaw, byId, "raw.periodic_state_snapshot", "local.state.periodicSnapshot", LocationClass.C, Transform.DROP, "AN-DEL-07");
        reg(byRaw, byId, "raw.consent_state_mirror", "local.meta.consentMirror", LocationClass.C, Transform.DROP, "AN-DEL-07");
        reg(byRaw, byId, "raw.deletion_receipt_paths", "local.config.deletionReceipt", LocationClass.C, Transform.DROP, "AN-DEL-04");
        reg(byRaw, byId, "raw.crash_log", "not_collected.crash_report", LocationClass.C, Transform.DROP, "AN-TXT-02", "AN-CON-04");
        reg(byRaw, byId, "raw.local_log", "local.log.mcinsightLog", LocationClass.C, Transform.DROP, "AN-GATE-02");
        reg(byRaw, byId, "manifest.export_meta", "export.manifest", LocationClass.A, Transform.KEEP, "AN-SHARE-03", "AN-LOCAL-01");
        reg(byRaw, byId, "manifest.export_id", "export.manifest.exportId", LocationClass.A, Transform.KEEP, "AN-LOCAL-01");
        reg(byRaw, byId, "manifest.player_count", "export.manifest.contributingPlayers", LocationClass.A, Transform.KEEP, "AN-AGG-03", "AN-AGG-06");
        reg(byRaw, byId, "report.pdf_text_layer", "export.reportPdf", LocationClass.A, Transform.KEEP, "AN-TXT-04", "AN-LOCAL-01");
        reg(byRaw, byId, "report.md_text", "export.reportMd", LocationClass.A, Transform.KEEP, "AN-TXT-04", "AN-GATE-01");
        reg(byRaw, byId, "package.redaction_report", "export.redactionReport", LocationClass.A, Transform.KEEP, "AN-GATE-03", "AN-DEL-04");
        reg(byRaw, byId, "pack.env_snapshot", "local.meta.packSnapshot", LocationClass.B, Transform.GENERALIZE_CATEGORY, "AN-TXT-09", "AN-SHARE-06");
        reg(byRaw, byId, "pack.mod_id", "local.meta.packSnapshot.mods", LocationClass.B, Transform.KEEP_ID_WHITELIST, "AN-TXT-09", "AN-REG-01");
        reg(byRaw, byId, "pack.mod_content_hash", "local.meta.packSnapshot.mods[].sha256", LocationClass.B, Transform.HASH_SALT_TRUNC64, "AN-TXT-09", "AN-REG-01");
        reg(byRaw, byId, "pack.resourcepack_id", "local.meta.packSnapshot.resourcepacks", LocationClass.B, Transform.HASH_SALT_TRUNC64, "AN-TXT-09", "AN-REG-01", "AN-SHARE-06");
        reg(byRaw, byId, "pack.datapack_id", "local.meta.packSnapshot.datapacks", LocationClass.B, Transform.HASH_SALT_TRUNC64, "AN-TXT-09", "AN-REG-01", "AN-SHARE-06");
        reg(byRaw, byId, "pack.set_fingerprint", "local.meta.packSnapshot (组合键)", LocationClass.B, Transform.HASH_SALT_TRUNC64, "AN-REG-02", "AN-AGG-14");
        reg(byRaw, byId, "pack.set_class", "local.meta.packSnapshot (类别派生)", LocationClass.B, Transform.GENERALIZE_CATEGORY, "AN-TXT-09", "AN-AGG-14");
        reg(byRaw, byId, "pack.representation_forms", "local.config.representationForms", LocationClass.C, Transform.DROP, "AN-TXT-09", "AN-REG-01", "AN-REG-05");
        reg(byRaw, byId, "analysis.metric_status", "analysis.metrics[].status", LocationClass.A, Transform.KEEP_ENUM, "AN-AGG-06", "AN-AGG-07", "AN-GATE-04");
        reg(byRaw, byId, "analysis.suppression_reason_code", "analysis.metrics[].reasonCode", LocationClass.A, Transform.KEEP_ENUM, "AN-AGG-03", "AN-GATE-04");
        reg(byRaw, byId, "analysis.suppression_rule", "analysis.metrics[].suppressedByRule", LocationClass.A, Transform.KEEP_ENUM, "AN-AGG-04", "AN-AGG-06");
        reg(byRaw, byId, "analysis.suppression_detail", "analysis.metrics[].detail", LocationClass.A, Transform.GENERALIZE_REASON_CODE, "AN-AGG-06");
        reg(byRaw, byId, "analysis.support_counts", "analysis.metrics[].support", LocationClass.A, Transform.KEEP, "AN-AGG-06", "AN-AGG-09");
        reg(byRaw, byId, "analysis.individual_view_scope", "analysis.aggregation.individualView", LocationClass.A, Transform.KEEP_ENUM, "AN-AGG-05");
        reg(byRaw, byId, "analysis.effective_constants", "analysis.aggregation.effectiveConstants", LocationClass.A, Transform.KEEP_ENUM, "AN-AGG-01", "AN-AGG-08");
        reg(byRaw, byId, "analysis.suppression_summary", "analysis.suppressionSummary", LocationClass.A, Transform.KEEP, "AN-AGG-06");
        reg(byRaw, byId, "analysis.metric_status_pointer", "analysis.conclusions[].metricStatusPointer", LocationClass.A, Transform.KEEP_ENUM, "AN-AGG-10");
        reg(byRaw, byId, "analysis.s2_confirmation_record", "export.redaction.s2Confirmations", LocationClass.A, Transform.KEEP, "AN-SHARE-02");
        reg(byRaw, byId, "analysis.s3_absent_fields", "export.redaction.s3AbsentFields", LocationClass.A, Transform.KEEP_ENUM, "AN-SHARE-04", "AN-SHARE-03");
        reg(byRaw, byId, "session_environment.snapshotRev", "session_environment.snapshotRev", LocationClass.B, Transform.KEEP, "AN-TXT-07", "AN-REG-11");
        reg(byRaw, byId, "session_environment.envSchemaVersion", "session_environment.envSchemaVersion", LocationClass.A, Transform.KEEP, "AN-TXT-07", "AN-REG-11");
        reg(byRaw, byId, "session_environment.snapshotDigest16", "session_environment.snapshotDigest16", LocationClass.C, Transform.DROP_COUNT_METADATA, "AN-TXT-07", "AN-REG-11");
        reg(byRaw, byId, "session_environment.mod_count", "local.meta.envSnapshot.modCount", LocationClass.B, Transform.GENERALIZE_CATEGORY, "AN-TXT-07", "AN-REG-11");
        reg(byRaw, byId, "session_environment.mod_count_by_source_class", "local.meta.envSnapshot.modCountBySourceClass", LocationClass.B, Transform.GENERALIZE_CATEGORY, "AN-TXT-07", "AN-REG-11");
        reg(byRaw, byId, "session_environment.resource_pack_count", "local.meta.envSnapshot.resourcePackCount", LocationClass.B, Transform.GENERALIZE_CATEGORY, "AN-TXT-07", "AN-REG-11");
        reg(byRaw, byId, "session_environment.data_pack_count", "local.meta.envSnapshot.dataPackCount", LocationClass.B, Transform.GENERALIZE_CATEGORY, "AN-TXT-07", "AN-REG-11");
        reg(byRaw, byId, "session_environment.resource_packs_available", "local.meta.envSnapshot.resourcePacksAvailable", LocationClass.B, Transform.KEEP_ENUM, "AN-TXT-07", "AN-REG-11");
        reg(byRaw, byId, "session_environment.data_packs_available", "local.meta.envSnapshot.dataPacksAvailable", LocationClass.B, Transform.KEEP_ENUM, "AN-TXT-07", "AN-REG-11");

        BY_RAW_FIELD = Collections.unmodifiableMap(byRaw);
        BY_FIELD_ID = Collections.unmodifiableMap(byId);
        KNOWN_FIELD_NAME_SUFFIXES = buildKnownFieldNameSuffixes();
    }

    private FieldRegistry() {
    }

    private static void reg(Map<String, FieldPolicy> byRaw, Map<String, FieldPolicy> byId,
                            String fieldId, String rawField, LocationClass locationClass,
                            Transform transform, String... acceptance) {
        FieldPolicy policy = new FieldPolicy(rawField, fieldId, locationClass, transform,
                Set.of(acceptance));
        FieldPolicy previous = byRaw.get(rawField);
        if (previous == null
                || policy.locationClass().strictness() < previous.locationClass().strictness()) {
            byRaw.put(rawField, policy);
        }
        byId.put(fieldId, policy);
    }

    public static Optional<FieldPolicy> lookup(String rawField) {
        return Optional.ofNullable(BY_RAW_FIELD.get(rawField));
    }

    public static boolean isRegisteredRawField(String rawField) {
        return BY_RAW_FIELD.containsKey(rawField);
    }

    public static boolean isUnknownFieldName(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return true;
        }
        if (KNOWN_FIELD_NAME_SUFFIXES.contains(fieldName)) {
            return false;
        }
        return !KNOWN_FIELD_NAME_SUFFIXES.contains(toSnakeCase(fieldName));
    }

    public static Set<String> knownFieldNameSuffixes() {
        return KNOWN_FIELD_NAME_SUFFIXES;
    }

    public static Map<String, String> fieldNameEquivalents() {
        return FIELD_NAME_EQUIVALENTS;
    }

    private static final Map<String, String> FIELD_NAME_EQUIVALENTS =
            Collections.unmodifiableMap(new LinkedHashMap<>());

    public static String canonicalFieldName(String fieldName) {
        if (fieldName == null) {
            return null;
        }
        String mapped = FIELD_NAME_EQUIVALENTS.get(fieldName);
        return mapped != null ? mapped : fieldName;
    }

    public static List<FieldPolicy> allPoliciesForName(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return List.of();
        }
        String canonical = canonicalFieldName(fieldName);
        List<FieldPolicy> out = new java.util.ArrayList<>();
        for (FieldPolicy p : BY_FIELD_ID.values()) {
            String raw = p.rawField();
            int dot = raw.lastIndexOf('.');
            String suffix = dot >= 0 ? raw.substring(dot + 1) : raw;
            if (suffix.equals(canonical) || suffix.equals(fieldName)) {
                out.add(p);
            }
        }
        out.sort((a, b) -> {
            int c = Integer.compare(a.locationClass().strictness(), b.locationClass().strictness());
            if (c != 0) {
                return c;
            }
            return Boolean.compare(b.transform().isIrreversible(), a.transform().isIrreversible());
        });
        return List.copyOf(out);
    }

    public static boolean hasAnyPolicyForName(String fieldName) {
        return !allPoliciesForName(fieldName).isEmpty();
    }

    private static final Set<String> KNOWN_FIELD_NAME_SUFFIXES;
    private static final Map<String, String> SNAKE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static String toSnakeCase(String s) {
        return SNAKE_CACHE.computeIfAbsent(s, k -> {
            StringBuilder sb = new StringBuilder(k.length() + 8);
            for (int i = 0; i < k.length(); i++) {
                char c = k.charAt(i);
                if (Character.isUpperCase(c) && i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            }
            return sb.toString();
        });
    }

    private static Set<String> buildKnownFieldNameSuffixes() {
        Set<String> suffixes = new LinkedHashSet<>();
        for (FieldPolicy p : BY_FIELD_ID.values()) {
            String raw = p.rawField();
            int dot = raw.indexOf('.');
            if (dot > 0 && dot < raw.length() - 1) {
                suffixes.add(raw.substring(dot + 1));
            }
        }
        return Collections.unmodifiableSet(suffixes);
    }

    public static List<FieldPolicy> allPoliciesFor(String rawField) {
        List<FieldPolicy> out = new java.util.ArrayList<>();
        for (FieldPolicy p : BY_FIELD_ID.values()) {
            if (p.rawField().equals(rawField)) {
                out.add(p);
            }
        }
        return List.copyOf(out);
    }

    public static Optional<FieldPolicy> strictestFor(String rawField) {
        FieldPolicy best = null;
        for (FieldPolicy p : allPoliciesFor(rawField)) {
            if (isDeprecated(p)) {
                continue;
            }
            if (best == null || isStricter(p, best)) {
                best = p;
            }
        }
        return Optional.ofNullable(best);
    }

    public static boolean isDeprecated(FieldPolicy policy) {
        return policy != null && DEPRECATED_FIELD_IDS.contains(policy.fieldId());
    }

    public static Set<String> deprecatedFieldIds() {
        return DEPRECATED_FIELD_IDS;
    }

    public static List<String> strictestDrivenByDeprecated() {
        List<String> bad = new java.util.ArrayList<>();
        Set<String> allRaw = registeredRawFields();
        for (String raw : allRaw) {
            FieldPolicy best = null;
            FieldPolicy bestAny = null;
            for (FieldPolicy p : allPoliciesFor(raw)) {
                if (bestAny == null || isStricter(p, bestAny)) {
                    bestAny = p;
                }
                if (isDeprecated(p)) {
                    continue;
                }
                if (best == null || isStricter(p, best)) {
                    best = p;
                }
            }
            if (bestAny != null && isDeprecated(bestAny)) {
                bad.add(raw);
            }
        }
        return List.copyOf(bad);
    }

    private static boolean isStricter(FieldPolicy candidate, FieldPolicy current) {
        if (candidate.locationClass().strictness() != current.locationClass().strictness()) {
            return candidate.locationClass().strictness() < current.locationClass().strictness();
        }
        return candidate.transform().isIrreversible() && !current.transform().isIrreversible();
    }

    public static Optional<FieldPolicy> byFieldId(String fieldId) {
        return Optional.ofNullable(BY_FIELD_ID.get(fieldId));
    }

    public static Set<String> registeredRawFields() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(BY_RAW_FIELD.keySet()));
    }

    public static List<FieldPolicy> all() {
        return List.copyOf(BY_FIELD_ID.values());
    }

    public static Set<String> implementedTransformIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Transform t : Transform.values()) {
            ids.add(t.specId());
        }
        return Collections.unmodifiableSet(ids);
    }

    public static Optional<Violation> checkCollection(String eventType, String fieldPath) {
        String raw = eventType + "." + fieldPath;
        FieldPolicy policy = BY_RAW_FIELD.get(raw);
        if (policy == null) {
            if (isUnknownFieldName(fieldPath)) {
                return Optional.of(new Violation(eventType, fieldPath,
                        ViolationReason.UNREGISTERED_FIELD, null));
            }
            return Optional.empty();
        }
        if (policy.mustNotBeCollected()) {
            return Optional.of(new Violation(eventType, fieldPath,
                    ViolationReason.NOT_COLLECTED_CLASS_C, policy.transform().specId()));
        }
        return Optional.empty();
    }

    public static void requireIrreversible(String eventType, String fieldPath) {
        String raw = eventType + "." + fieldPath;
        FieldPolicy policy = BY_RAW_FIELD.get(raw);
        if (policy == null) {
            return;
        }
        if (policy.requiresIrreversibleTransformAtCollection()) {
            return;
        }
        if (policy.locationClass() == LocationClass.B && !policy.transform().isIrreversible()) {
            throw new ContractException("B 类字段 " + raw + " 的处置为 "
                    + policy.transform().specId() + "，必须按登记处置而非原样写入");
        }
    }
}
