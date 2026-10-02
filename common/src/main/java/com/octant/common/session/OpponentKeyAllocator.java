package com.octant.common.session;

import com.octant.common.model.ContractException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class OpponentKeyAllocator {

    public enum OpponentKind {
        OTHER_PLAYER("other_player"),
        SELF("self");

        private final String code;

        OpponentKind(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Map<String, Integer> perTypeSeq = new LinkedHashMap<>();

    public String allocate(String entityType) {
        requireId(entityType);
        int next = perTypeSeq.getOrDefault(entityType, 0) + 1;
        perTypeSeq.put(entityType, next);
        return entityType + "#" + next;
    }

    public int allocatedCount(String entityType) {
        return perTypeSeq.getOrDefault(entityType, 0);
    }

    public int distinctTypes() {
        return perTypeSeq.size();
    }

    public int totalAllocated() {
        int sum = 0;
        for (int v : perTypeSeq.values()) {
            sum += v;
        }
        return sum;
    }

    public static void checkShape(String opponentKey) {
        Objects.requireNonNull(opponentKey, "opponentKey");
        if (looksLikeUuid(opponentKey)) {
            throw new ContractException("opponentKey 不得承载实体 UUID（REQ-PRIV-01）：" + opponentKey);
        }
        int hash = opponentKey.lastIndexOf('#');
        if (hash <= 0 || hash == opponentKey.length() - 1) {
            throw new ContractException(
                    "opponentKey 必须形如 <entityType>#<n>（dc §2.4）：" + opponentKey);
        }
        String type = opponentKey.substring(0, hash);
        String seq = opponentKey.substring(hash + 1);
        if (looksLikeUuid(type) || type.replace("-", "").matches("[0-9a-fA-F]{32,}")) {
            throw new ContractException("opponentKey 不得承载实体 UUID（REQ-PRIV-01）：" + opponentKey);
        }
        requireId(type);
        for (int i = 0; i < seq.length(); i++) {
            if (!Character.isDigit(seq.charAt(i))) {
                throw new ContractException("opponentKey 的实例序号必须为整数： " + opponentKey);
            }
        }
        if (Integer.parseInt(seq) < 1) {
            throw new ContractException("opponentKey 的实例序号必须 ≥ 1：" + opponentKey);
        }
    }

    private static boolean looksLikeUuid(String value) {
        String compact = value.replace("-", "");
        return compact.length() == 32 && compact.matches("[0-9a-fA-F]{32}");
    }

    private static void requireId(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            throw new ContractException("entityType 不得为空");
        }
        for (int i = 0; i < entityType.length(); i++) {
            char c = entityType.charAt(i);
            if (c <= ' ' || Character.isISOControl(c)) {
                throw new ContractException(
                        "entityType 含空白或控制字符，禁止自定义名/自由文本（REQ-PRIV-01）：" + entityType);
            }
        }
    }
}
