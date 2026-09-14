package com.rappi.buyer.planner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mockito tests prove the arithmetic. This one proves the same arithmetic
 * still holds against the real seeded database - schema, flyway data and all.
 *
 * Needs mysql and redis up (docker compose up -d), so it is tagged IT and does
 * not run in the normal unit test pass.
 */
@SpringBootTest
class ReorderPlannerSeedIT {

    @Autowired
    ReorderPlanner planner;

    @Test
    @DisplayName("scenario 1: the 800 unit recommendation is wrong, real answer is 204")
    void milkAgainstRealSeed() {
        ReorderPlan p = planner.plan("SKU-MILK-1L", "NODE-BOG-01", "SUP-LACTEO");

        System.out.println("--- scenario 1 derivation ---");
        p.explanationSteps().forEach(s -> System.out.println("  " + s));
        p.warnings().forEach(w -> System.out.println("  WARN " + w));
        System.out.printf("  recommended %d units, $%s, %s days cover%n",
                p.recommendedQty(), p.estimatedCost(), p.daysOfCoverAfter());

        assertThat(p.inventoryPosition()).isEqualTo(680);
        assertThat(p.protectionPeriodDays()).isEqualTo(12);
        assertThat(p.recommendedQty()).isEqualTo(204);
        assertThat(p.estimatedCost()).isEqualByComparingTo("193.80");

        // the whole point of the case: 204 is nothing like the 800 that was suggested
        assertThat(p.recommendedQty()).isLessThan(300);
        assertThat(p.warnings()).isEmpty();
    }

    @Test
    @DisplayName("scenario 4: rice has no legal order, budget and moq cannot both be met")
    void riceIsBlocked() {
        ReorderPlan p = planner.plan("SKU-RICE-5KG", "NODE-BOG-01", "SUP-ARROZMX");

        System.out.println("--- scenario 4 derivation ---");
        p.explanationSteps().forEach(s -> System.out.println("  " + s));

        assertThat(p.rawNeed()).isPositive();
        assertThat(p.maxAffordable()).isLessThan(1000);   // moq is 1000
        assertThat(p.recommendedQty()).isZero();
        assertThat(p.blocked()).isTrue();
    }
}
