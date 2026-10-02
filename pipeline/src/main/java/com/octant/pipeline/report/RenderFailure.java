package com.octant.pipeline.report;

import java.util.LinkedHashSet;
import java.util.List;

public class RenderFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE";

    private final String reasonCode;
    private final List<Integer> codePoints;

    public RenderFailure(String reasonCode, String message, List<Integer> codePoints) {
        super(message);
        this.reasonCode = reasonCode;
        this.codePoints = List.copyOf(new LinkedHashSet<>(codePoints));
    }

    public String reasonCode() {
        return reasonCode;
    }

    public List<Integer> codePoints() {
        return codePoints;
    }

    public boolean glyphUnavailable() {
        return SOURCE_UNAVAILABLE.equals(reasonCode);
    }

    public static String describe(List<Integer> codePoints, int limit) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int cp : codePoints) {
            if (n++ >= limit) {
                sb.append("…（共 ").append(codePoints.size()).append(" 个）");
                break;
            }
            sb.append(String.format("U+%04X ", cp));
        }
        return sb.toString().trim();
    }
}
