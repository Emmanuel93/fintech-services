package com.fintech.collections.domain;

/** ES-01: strategy determined by bucket — kept local/config-driven rather than hardcoded logic elsewhere. */
public final class CollectionStrategyResolver {

    private CollectionStrategyResolver() {}

    public static String resolve(DelinquencyBucket bucket) {
        return switch (bucket) {
            case CURRENT     -> "PRE_DUE_REMINDER";
            case B1_30       -> "AUTO_NOTIFY";
            case B31_60      -> "AGENT_ASSIGNED_RESTRUCTURE_OFFER";
            case B61_90      -> "INTENSIVE_LEGAL_PREVENTIVE";
            case B91_120     -> "PRE_WRITEOFF_EXTERNAL_AGENCY";
            case B121_180    -> "EXTERNAL_AGENCY_QUITA_OFFER";
            case B181_PLUS   -> "WRITEOFF_CANDIDATE";
        };
    }
}
