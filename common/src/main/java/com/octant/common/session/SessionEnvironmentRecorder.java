package com.octant.common.session;

import com.octant.common.model.CaptureEvent;
import com.octant.common.model.CaptureEventType;
import com.octant.common.model.ContractException;
import com.octant.common.model.EventSource;
import com.octant.common.platforms.PlatformAdapter;
import com.octant.common.privacy.EnvSnapshot;
import com.octant.common.privacy.PackSnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class SessionEnvironmentRecorder {

    private String lastSessionId;
    public SessionEnvironmentRecorder() {
    }

    public record SessionIdentity(String sessionId, String playerKey, EventSource source,
                                  long observedAtRelMs, int eventSeqInSession) {

        public SessionIdentity {
            if (sessionId == null || playerKey == null || source == null) {
                throw new ContractException("会话身份三要素不得为 null");
            }
            if (observedAtRelMs < 0) {
                throw new ContractException("observedAtRelMs 不得为负：" + observedAtRelMs);
            }
        }
    }

    public record Emitted(boolean emitted, String reason, Path detailFile, String digest16) {

        public static Emitted rejected(String reason) {
            return new Emitted(false, reason, null, null);
        }
    }

    public Emitted emit(Path worldDir, PackSnapshot packs, int rev, SessionIdentity id,
                        PlatformAdapter.EventSink events) {
        java.util.Objects.requireNonNull(worldDir, "worldDir");
        java.util.Objects.requireNonNull(packs, "packs");
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(events, "events");

        if (id.sessionId().equals(lastSessionId)) {
            return Emitted.rejected("DUPLICATE_WITHIN_SESSION");
        }

        EnvSnapshot env = new EnvSnapshot(packs, rev);
        CaptureEvent event = new CaptureEvent(
                com.octant.common.model.RawEventSchema.VERSION,
                CaptureEvent.eventId(id.eventSeqInSession()),
                id.sessionId(),
                id.playerKey(),
                CaptureEventType.SESSION_ENVIRONMENT,
                id.observedAtRelMs(),
                CaptureEvent.tickOf(id.observedAtRelMs()),
                CaptureEventType.SESSION_ENVIRONMENT.category(),
                id.source(),
                true,
                null,
                env.toEventPayload(id.observedAtRelMs()));

        if (!events.emit(event)) {
            return Emitted.rejected("REJECTED_BY_CONSENT_GATE");
        }

        Path detail;
        try {
            detail = env.writeDetail(worldDir);
        } catch (IOException e) {
            throw new UncheckedIOException("环境明细落盘失败（事件已发，明细缺失即为不一致）：" + e.getMessage(), e);
        }
        env.verifyDetailReproducible(worldDir);

        lastSessionId = id.sessionId();
        return new Emitted(true, "OK", detail, env.detailDigest16());
    }

    public static boolean detailExists(Path worldDir, int rev) {
        return Files.isRegularFile(EnvSnapshot.detailPath(worldDir, rev));
    }

    public static Map<String, Object> payloadOf(PackSnapshot packs, int rev, long observedAtRelMs) {
        return new EnvSnapshot(packs, rev).toEventPayload(observedAtRelMs);
    }
}
