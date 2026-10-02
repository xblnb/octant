package com.octant.pipeline.analysis;

public final class ChartForms {

    public static final String KPI_UNIT = "C1";
    public static final String SHARE_BAR = "C2";
    public static final String HORIZONTAL_BAR = "C3";
    public static final String HISTOGRAM = "C4";
    public static final String LINE = "C5";
    public static final String STEP_LINE = "C6";
    public static final String SCATTER = "C7";
    public static final String HEATMAP = "C8";
    public static final String TIMELINE_BAND = "C9";
    public static final String DUMBBELL = "C10";
    public static final String SUPPRESSED_PLACEHOLDER = "C11";
    public static final String BULLET_BAR = "C12";
    public static final String BOX_PLOT = "C13";
    public static final String PROFILE_PARALLEL = "C14";
    public static final String COVERAGE_BAR = "C15";
    public static final String CAUSE_MATRIX = "C16";
    public static final String DEVIATION_BAR = "C17";

    public static final java.util.List<String> ALL_FORMS = java.util.List.of(
            KPI_UNIT, SHARE_BAR, HORIZONTAL_BAR, HISTOGRAM, LINE, STEP_LINE, SCATTER, HEATMAP,
            TIMELINE_BAND, DUMBBELL, SUPPRESSED_PLACEHOLDER, BULLET_BAR, BOX_PLOT,
            PROFILE_PARALLEL, COVERAGE_BAR, CAUSE_MATRIX, DEVIATION_BAR);

    private ChartForms() {
    }

    public static String forMetric(String metricId) {
        return switch (metricId) {
            case "M1a" -> COVERAGE_BAR;
            case "M1b" -> KPI_UNIT;
            case "M1c" -> BULLET_BAR;
            case "M1-tail" -> KPI_UNIT;
            case "M2a" -> KPI_UNIT;
            case "M2b" -> COVERAGE_BAR;
            case "M2c" -> HORIZONTAL_BAR;
            case "M2d" -> BULLET_BAR;
            case "M3a" -> KPI_UNIT;
            case "M3b" -> HISTOGRAM;
            case "M3c" -> LINE;
            case "M3d" -> KPI_UNIT;
            case "M3e" -> BULLET_BAR;
            case "M4" -> SHARE_BAR;
            case "M5a" -> BULLET_BAR;
            case "M5b" -> BULLET_BAR;
            case "M5c", "M5d" -> KPI_UNIT;
            case "M6a" -> SHARE_BAR;
            case "M6b", "M6c", "M6d" -> KPI_UNIT;
            case "M6e" -> HORIZONTAL_BAR;
            case "M7a", "M7b", "M7c" -> BULLET_BAR;
            case "M7d", "M7e" -> HORIZONTAL_BAR;
            case "M8a" -> COVERAGE_BAR;
            case "M8b", "M8c" -> BULLET_BAR;
            case "M8d" -> BOX_PLOT;
            case "M8e", "M8f" -> KPI_UNIT;
            case "M8g" -> SHARE_BAR;
            case "D1_PACE", "D2_DEPTH", "D3_BREADTH", "D4_DISPERSION", "D5_CRAFT", "D6_COMBAT",
                 "D7_PERSIST" -> PROFILE_PARALLEL;
            case "M9_SEGMENT" -> TIMELINE_BAND;
            default -> KPI_UNIT;
        };
    }
}
