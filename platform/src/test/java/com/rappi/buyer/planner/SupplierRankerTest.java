package com.rappi.buyer.planner;

import com.rappi.buyer.config.PlanningProperties;
import com.rappi.buyer.domain.Supplier;
import com.rappi.buyer.domain.SupplierProduct;
import com.rappi.buyer.repo.SupplierProductRepo;
import com.rappi.buyer.repo.SupplierRepo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** The coffee case: cheap fast ANDINA that ships 250 a day, dear slow CAFEBR that ships 2000. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupplierRankerTest {

    static final String SKU = "SKU-COFFEE-500G";
    static final String NODE = "NODE-BOG-01";

    @Mock SupplierProductRepo supplierProducts;
    @Mock SupplierRepo suppliers;
    @Mock ReorderPlanner planner;

    SupplierRanker ranker;
    List<SupplierProduct> offers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        PlanningProperties props = new PlanningProperties(
                Map.of("A", 0.98, "B", 0.95, "C", 0.90),
                new PlanningProperties.Cover(30, 45),
                new PlanningProperties.Overbuy(1.25, 1.50),
                new PlanningProperties.PriceVariance(0.10, 0.25),
                new BigDecimal("1000.00"), new BigDecimal("0.50"),
                new PlanningProperties.Weights(0.35, 0.35, 0.20, 0.10));
        ranker = new SupplierRanker(supplierProducts, suppliers, planner, props);
        when(supplierProducts.findBySku(SKU)).thenReturn(offers);
    }

    @Test
    @DisplayName("cheaper and fast enough wins, and every part of the score is shown")
    void cheapestFastEnoughWins() {
        offer("SUP-ANDINA", true, "0.88", 100, 250, plan("SUP-ANDINA", "3.90", 4, 150, 150, "9.0"));
        offer("SUP-CAFEBR", true, "0.91", 100, 2000, plan("SUP-CAFEBR", "4.40", 9, 400, 400, "9.0"));

        List<SupplierRanker.Option> r = ranker.rank(SKU, NODE);

        assertThat(r.get(0).supplierId()).isEqualTo("SUP-ANDINA");
        assertThat(r.get(0).parts().get("price")).isZero();
        assertThat(r.get(1).parts().get("price")).isEqualTo(0.128);   // 4.40 vs 3.90
        assertThat(r.get(1).parts().get("leadTime")).isZero();       // lands before the shelf empties
    }

    @Test
    @DisplayName("a lead time only counts against a supplier if it lands after the shelf runs out")
    void lateDeliveryIsPenalised() {
        offer("SUP-ANDINA", true, "0.88", 100, 250, plan("SUP-ANDINA", "3.90", 4, 150, 150, "5.0"));
        offer("SUP-CAFEBR", true, "0.91", 100, 2000, plan("SUP-CAFEBR", "4.40", 9, 400, 400, "5.0"));

        List<SupplierRanker.Option> r = ranker.rank(SKU, NODE);

        assertThat(r.get(1).supplierId()).isEqualTo("SUP-CAFEBR");
        assertThat(r.get(1).parts().get("leadTime")).isEqualTo(0.8);  // (9 - 5) / 5
        assertThat(r.get(0).parts().get("leadTime")).isZero();
    }

    @Test
    @DisplayName("a plan bigger than the daily capacity gets a note")
    void capacityIsCalledOut() {
        offer("SUP-ANDINA", true, "0.88", 100, 250, plan("SUP-ANDINA", "3.90", 4, 500, 500, "9.0"));
        assertThat(ranker.rank(SKU, NODE).get(0).note()).contains("over the 250 a day");
    }

    @Test
    @DisplayName("an inactive supplier is listed last and never usable")
    void inactiveIsUnusable() {
        offer("SUP-GRANOS", false, "0.72", 400, 2000, null);
        offer("SUP-ANDINA", true, "0.88", 100, 250, plan("SUP-ANDINA", "3.90", 4, 150, 150, "9.0"));

        List<SupplierRanker.Option> r = ranker.rank(SKU, NODE);

        assertThat(r.get(0).supplierId()).isEqualTo("SUP-ANDINA");
        assertThat(r.get(1).supplierId()).isEqualTo("SUP-GRANOS");
        assertThat(r.get(1).usable()).isFalse();
        assertThat(r.get(1).plan()).isNull();
    }

    @Test
    @DisplayName("a minimum far above the need is penalised")
    void moqOverBuyIsPenalised() {
        offer("SUP-ANDINA", true, "0.88", 400, 250, plan("SUP-ANDINA", "3.90", 4, 100, 400, "9.0"));
        assertThat(ranker.rank(SKU, NODE).get(0).parts().get("moq")).isEqualTo(3.0);   // (400-100)/100
    }

    // ---- fixtures ----------------------------------------------------------

    void offer(String id, boolean active, String reliability, int moq, int capacity, ReorderPlan plan) {
        Supplier s = new Supplier();
        s.setId(id);
        s.setName(id);
        s.setActive(active);
        s.setMoqUnits(moq);
        s.setReliabilityScore(new BigDecimal(reliability));
        when(suppliers.findById(id)).thenReturn(Optional.of(s));

        SupplierProduct sp = new SupplierProduct();
        sp.setSupplierId(id);
        sp.setSku(SKU);
        sp.setMinOrderUnits(moq);
        sp.setMaxDailyCapacity(capacity);
        offers.add(sp);

        if (plan != null) {
            when(planner.plan(SKU, NODE, id)).thenReturn(plan);
        }
    }

    ReorderPlan plan(String supplier, String price, int leadTime, int rawNeed, int qty, String coverNow) {
        BigDecimal p = new BigDecimal(price);
        return new ReorderPlan(SKU, NODE, supplier,
                180, 20, 250, 410,
                leadTime, 7, leadTime + 7,
                new BigDecimal("500"), new BigDecimal("43"),
                100, 0.98, 2.05,
                600, rawNeed, rawNeed, qty, 5000, 5000, qty,
                new BigDecimal(coverNow), new BigDecimal("15"), p,
                p.multiply(BigDecimal.valueOf(qty)), List.of(), List.of());
    }
}
