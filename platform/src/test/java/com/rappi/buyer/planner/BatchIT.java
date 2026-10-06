package com.rappi.buyer.planner;

import com.rappi.buyer.domain.PlanningException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A real batch over the seeded stores. Needs docker compose up. */
@SpringBootTest
class BatchIT {

    @Autowired BatchPlanner batch;

    List<String> kindsFor(BatchPlanner.Summary s, String node, String sku) {
        return s.exceptions().stream()
                .filter(e -> e.getNodeId().equals(node) && e.getSku().equals(sku))
                .map(PlanningException::getKind).toList();
    }

    @Test
    @DisplayName("the batch finds the seeded problems and leaves the explained spike alone")
    void findsTheSeededCases() {
        BatchPlanner.Summary s = batch.run();
        s.exceptions().forEach(e -> System.out.printf("  %-12s %-16s %-15s %s%n",
                e.getNodeId(), e.getSku(), e.getKind(), e.getDetail()));

        assertThat(s.scanned()).isGreaterThanOrEqualTo(6);
        assertThat(kindsFor(s, "NODE-BOG-01", "SKU-RICE-5KG")).contains("NO_LEGAL_ORDER");
        assertThat(kindsFor(s, "NODE-BOG-01", "SKU-CHIPS-150G")).contains("DEMAND_SHIFT");
        // the soda spike is a promotion plus one bulk order - not a shift
        assertThat(kindsFor(s, "NODE-BOG-01", "SKU-SODA-2L")).doesNotContain("DEMAND_SHIFT");
        assertThat(s.exceptions()).allMatch(e -> e.getBatchId().equals(s.batchId()));
    }
}
