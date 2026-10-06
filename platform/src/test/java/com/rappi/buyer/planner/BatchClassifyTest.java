package com.rappi.buyer.planner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** classify() is pure, so no mocks and no database - just inputs and what it flags. */
class BatchClassifyTest {

    static List<String> kinds(List<BatchPlanner.Flag> flags) {
        return flags.stream().map(BatchPlanner.Flag::kind).toList();
    }

    @Test
    @DisplayName("a healthy sku is not flagged at all")
    void healthy() {
        assertThat(BatchPlanner.classify(plan(0, 0, List.of()), quiet(), cover(0))).isEmpty();
    }

    @Test
    @DisplayName("a need with a legal order is NEEDS_ORDER, carrying the planner's quantity")
    void needsOrder() {
        List<BatchPlanner.Flag> f = BatchPlanner.classify(plan(156, 204, List.of()), quiet(), cover(0));
        assertThat(kinds(f)).containsExactly("NEEDS_ORDER");
        assertThat(f.get(0).suggestedQty()).isEqualTo(204);
    }

    @Test
    @DisplayName("a need with no legal order is NO_LEGAL_ORDER, not NEEDS_ORDER")
    void noLegalOrder() {
        assertThat(kinds(BatchPlanner.classify(plan(713, 0, List.of()), quiet(), cover(0))))
                .containsExactly("NO_LEGAL_ORDER");
    }

    @Test
    @DisplayName("data problems and a coming stockout are each their own flag")
    void dataAndStockout() {
        ReorderPlan p = plan(0, 0, List.of("DATA_CONFLICT: inventory.in_transit is 400 but open purchase orders sum to 700",
                "forecast was generated 136 hours ago"));
        assertThat(kinds(BatchPlanner.classify(p, quiet(), cover(3))))
                .containsExactly("DATA_CONFLICT", "STALE_DATA", "STOCKOUT_RISK");
    }

    @Test
    @DisplayName("only a REAL anomaly is a demand shift - an explained spike is not")
    void demandShift() {
        assertThat(kinds(BatchPlanner.classify(plan(0, 0, List.of()), anomaly(DemandAnomalyDetector.Verdict.REAL), cover(0))))
                .containsExactly("DEMAND_SHIFT");
        assertThat(BatchPlanner.classify(plan(0, 0, List.of()), anomaly(DemandAnomalyDetector.Verdict.EXPLAINED), cover(0)))
                .isEmpty();
    }

    @Test
    @DisplayName("no usable supplier means the sku cannot be planned")
    void noSupplier() {
        assertThat(kinds(BatchPlanner.classify(null, quiet(), null))).containsExactly("CANNOT_PLAN");
    }

    // ---- fixtures ----------------------------------------------------------

    static ReorderPlan plan(int rawNeed, int qty, List<String> warnings) {
        return new ReorderPlan("SKU-X", "NODE-BOG-01", "SUP-X",
                100, 0, 0, 100, 5, 7, 12,
                new BigDecimal("600"), new BigDecimal("50"), 100, 0.98, 2.05,
                700, rawNeed, rawNeed, rawNeed, 5000, 5000, qty,
                new BigDecimal("2.0"), new BigDecimal("6.0"), new BigDecimal("1.00"), new BigDecimal("0"),
                List.of("Need is " + rawNeed, "No legal order: 140 is affordable but the minimum is 1000"),
                warnings);
    }

    static CoverageSimulator.Coverage cover(int stockoutDays) {
        return new CoverageSimulator.Coverage(stockoutDays,
                stockoutDays > 0 ? LocalDate.of(2026, 3, 13) : null, 10, -5, BigDecimal.ONE, List.of());
    }

    static DemandAnomalyDetector.Anomaly quiet() {
        return anomaly(DemandAnomalyDetector.Verdict.INSUFFICIENT_DATA);
    }

    static DemandAnomalyDetector.Anomaly anomaly(DemandAnomalyDetector.Verdict v) {
        return new DemandAnomalyDetector.Anomaly(v, BigDecimal.TEN, BigDecimal.ONE, 14, false, List.of(),
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, "demand moved");
    }
}
