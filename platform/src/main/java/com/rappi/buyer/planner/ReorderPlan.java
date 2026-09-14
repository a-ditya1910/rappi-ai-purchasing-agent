package com.rappi.buyer.planner;

import java.math.BigDecimal;
import java.util.List;

/**
 * The full derivation, not just a number. explanationSteps exists so the agent
 * quotes computed facts instead of inventing them - it is the main reason the
 * numbers in the final answer can be trusted.
 */
public record ReorderPlan(
        String sku,
        String nodeId,
        String supplierId,

        int onHand,
        int reserved,
        int inTransit,
        int inventoryPosition,

        int leadTimeDays,
        int reviewPeriodDays,
        int protectionPeriodDays,

        BigDecimal expectedDemand,
        BigDecimal meanDailyDemand,
        int safetyStock,
        double serviceLevel,
        double z,

        int targetPosition,
        int rawNeed,
        int afterMoq,
        int afterCasePack,
        int maxAffordable,
        int maxStorable,
        int recommendedQty,

        BigDecimal daysOfCoverNow,
        BigDecimal daysOfCoverAfter,
        BigDecimal unitPrice,
        BigDecimal estimatedCost,

        List<String> explanationSteps,
        List<String> warnings) {

    /** No legal order exists - caller should escalate rather than order anyway. */
    public boolean blocked() {
        return recommendedQty == 0 && rawNeed > 0;
    }
}
