package com.octant.common;

public enum AttributionPriority {
    QUEST(0),
    DIMENSION(1),
    SYSTEM(2);

    private final int rank;

    AttributionPriority(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public static ContentAxis attribute(ContentAxis candidateA, AttributionPriority a,
                                       ContentAxis candidateB, AttributionPriority b) {
        return a.rank() <= b.rank() ? candidateA : candidateB;
    }
}
