package com.rappi.buyer.planner;

import com.rappi.buyer.config.PlanningProperties;
import com.rappi.buyer.domain.Budget;
import com.rappi.buyer.domain.DemandForecast;
import com.rappi.buyer.domain.Inventory;
import com.rappi.buyer.domain.Node;
import com.rappi.buyer.domain.Product;
import com.rappi.buyer.domain.Supplier;
import com.rappi.buyer.domain.SupplierProduct;
import com.rappi.buyer.repo.BudgetRepo;
import com.rappi.buyer.repo.ForecastRepo;
import com.rappi.buyer.repo.InventoryRepo;
import com.rappi.buyer.repo.NodeRepo;
import com.rappi.buyer.repo.ProductRepo;
import com.rappi.buyer.repo.PurchaseOrderRepo;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * No spring context, no database, no testcontainers - constructor injection plus
 * mockito is enough. Runs in milliseconds, which is why the maths gets this many
 * assertions instead of three.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReorderPlannerTest {

    static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    static final String SKU = "SKU-MILK-1L";
    static final String NODE = "NODE-BOG-01";
    static final String SUP = "SUP-LACTEO";

    @Mock ProductRepo products;
    @Mock NodeRepo nodes;
    @Mock SupplierRepo suppliers;
    @Mock SupplierProductRepo supplierProducts;
    @Mock InventoryRepo inventory;
    @Mock ForecastRepo forecasts;
    @Mock PurchaseOrderRepo purchaseOrders;
    @Mock BudgetRepo budgets;

    ReorderPlanner planner;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        planner = new ReorderPlanner(products, nodes, suppliers, supplierProducts, inventory,
                forecasts, purchaseOrders, budgets, planningProps(), clock);

        product(12, 1100, 12, true, "A");
        node(7, 40_000_000L, 34_000_000L);
        supplier(5, "1.0", 200, true);
        supplierProduct("0.95", 200, 4000);
        inventory(320, 40, 400);
        forecast(55, 12);
        when(purchaseOrders.sumIncomingBy(anyString(), anyString(), any())).thenReturn(400);
        budget("2500", "1620", "400");   // 480 available
    }

    // ---- the flagship case ------------------------------------------------

    @Test
    @DisplayName("milk: 800 recommendation is wrong, moq forces 204")
    void milkScenario() {
        ReorderPlan p = planner.plan(SKU, NODE, SUP);

        assertThat(p.inventoryPosition()).isEqualTo(680);
        assertThat(p.protectionPeriodDays()).isEqualTo(12);
        assertThat(p.rawNeed()).isLessThan(200);              // true need is under the moq
        assertThat(p.afterMoq()).isEqualTo(200);
        assertThat(p.recommendedQty()).isEqualTo(204);        // rounded to case pack of 12
        assertThat(p.recommendedQty() % 12).isZero();
        assertThat(p.estimatedCost()).isEqualByComparingTo("193.80");
        assertThat(p.warnings()).isEmpty();
    }

    @Test
    @DisplayName("explanation quotes the numbers it used")
    void explanationIsGrounded() {
        ReorderPlan p = planner.plan(SKU, NODE, SUP);
        assertThat(p.explanationSteps()).isNotEmpty();
        assertThat(String.join(" ", p.explanationSteps()))
                .contains("680")
                .contains("12 days")
                .contains("minimum order quantity");
    }

    // ---- position ---------------------------------------------------------

    @Test
    @DisplayName("reserved stock is not available stock")
    void reservedIsExcluded() {
        inventory(320, 100, 400);
        assertThat(planner.plan(SKU, NODE, SUP).inventoryPosition()).isEqualTo(620);
    }

    @Test
    @DisplayName("in transit is derived from open POs, not read off the column")
    void inTransitConflictIsFlagged() {
        inventory(320, 40, 400);
        when(purchaseOrders.sumIncomingBy(anyString(), anyString(), any())).thenReturn(700);

        ReorderPlan p = planner.plan(SKU, NODE, SUP);

        assertThat(p.inTransit()).isEqualTo(700);
        assertThat(p.warnings()).anyMatch(w -> w.startsWith("DATA_CONFLICT"));
    }

    @Test
    @DisplayName("negative on hand is clamped and flagged")
    void negativeStockClamped() {
        inventory(-20, 0, 0);
        ReorderPlan p = planner.plan(SKU, NODE, SUP);
        assertThat(p.onHand()).isZero();
        assertThat(p.warnings()).anyMatch(w -> w.contains("negative"));
    }

    // ---- already covered --------------------------------------------------

    @Test
    @DisplayName("nothing to do when the position already covers the target")
    void alreadyCovered() {
        when(purchaseOrders.sumIncomingBy(anyString(), anyString(), any())).thenReturn(2000);
        inventory(320, 40, 2000);

        ReorderPlan p = planner.plan(SKU, NODE, SUP);

        assertThat(p.rawNeed()).isZero();
        assertThat(p.recommendedQty()).isZero();
        assertThat(p.blocked()).isFalse();     // covered is not the same as blocked
    }

    // ---- moq / case pack --------------------------------------------------

    @Test
    @DisplayName("moq rounds up to a case pack multiple")
    void moqRoundsToCasePack() {
        supplier(5, "1.0", 200, true);
        product(12, 1100, 365, false, "A");    // non perishable so the spoilage gate stays out
        ReorderPlan p = planner.plan(SKU, NODE, SUP);
        assertThat(p.recommendedQty()).isEqualTo(204);
    }

    @Test
    @DisplayName("case pack of 1 leaves the quantity alone")
    void casePackOfOne() {
        product(1, 1100, 365, false, "A");
        ReorderPlan p = planner.plan(SKU, NODE, SUP);
        assertThat(p.recommendedQty()).isEqualTo(p.afterMoq());
    }

    // ---- the caps ---------------------------------------------------------

    @Test
    @DisplayName("budget and moq can be mutually unsatisfiable, which means no legal order")
    void budgetBelowMoqBlocks() {
        budget("200", "80", "0");              // 120 available -> 126 units -> 120 after case pack

        ReorderPlan p = planner.plan(SKU, NODE, SUP);

        assertThat(p.maxAffordable()).isLessThan(200);
        assertThat(p.recommendedQty()).isZero();
        assertThat(p.blocked()).isTrue();      // escalate, do not order 120 and get rejected
        assertThat(String.join(" ", p.explanationSteps())).contains("No legal order");
    }

    @Test
    @DisplayName("storage caps the order and rounds down, never up")
    void storageCaps() {
        product(12, 1100, 365, false, "A");
        node(7, 40_000_000L, 39_800_000L);     // 200_000 cm3 free -> 181 units -> 180
        supplier(5, "1.0", 12, true);
        supplierProduct("0.95", 12, 4000);

        ReorderPlan p = planner.plan(SKU, NODE, SUP);

        assertThat(p.maxStorable()).isEqualTo(180);
        assertThat(p.recommendedQty()).isLessThanOrEqualTo(180);
        assertThat(p.recommendedQty() % 12).isZero();
    }

    // ---- perishables ------------------------------------------------------

    @Test
    @DisplayName("moq that would outlive a short shelf life is refused")
    void perishableOverbuyRefused() {
        product(12, 1100, 3, true, "A");       // 3 day shelf life, block limit is 1.5x = 4.5 days
        supplier(5, "1.0", 2000, true);
        supplierProduct("0.95", 2000, 9000);
        budget("99999", "0", "0");

        ReorderPlan p = planner.plan(SKU, NODE, SUP);

        assertThat(p.recommendedQty()).isZero();
        assertThat(String.join(" ", p.explanationSteps())).contains("Better to order nothing");
    }

    // ---- safety stock -----------------------------------------------------

    @Test
    @DisplayName("zero forecast std is floored, otherwise safety stock is zero")
    void stdIsFloored() {
        forecast(55, 0);
        ReorderPlan p = planner.plan(SKU, NODE, SUP);
        assertThat(p.safetyStock()).isPositive();
        assertThat(p.warnings()).anyMatch(w -> w.contains("floored"));
    }

    @Test
    @DisplayName("an unreliable supplier gets a bigger buffer than a reliable one")
    void leadTimeVarianceRaisesSafetyStock() {
        supplier(5, "0.0", 200, true);
        int steady = planner.plan(SKU, NODE, SUP).safetyStock();

        supplier(5, "4.0", 200, true);
        int jittery = planner.plan(SKU, NODE, SUP).safetyStock();

        assertThat(jittery).isGreaterThan(steady);
    }

    @Test
    @DisplayName("class C buys less buffer than class A")
    void abcClassChangesZ() {
        product(12, 1100, 365, false, "A");
        int a = planner.plan(SKU, NODE, SUP).safetyStock();

        product(12, 1100, 365, false, "C");
        int c = planner.plan(SKU, NODE, SUP).safetyStock();

        assertThat(c).isLessThan(a);
    }

    @Test
    @DisplayName("protection period is lead time plus review period, not lead time alone")
    void protectionPeriodIncludesReviewPeriod() {
        supplier(5, "1.0", 200, true);
        node(14, 40_000_000L, 0L);
        assertThat(planner.plan(SKU, NODE, SUP).protectionPeriodDays()).isEqualTo(19);
    }

    // ---- staleness --------------------------------------------------------

    @Test
    @DisplayName("a stale forecast is flagged, not silently used")
    void staleForecastFlagged() {
        forecastGeneratedAt(TODAY.minusDays(9));
        assertThat(planner.plan(SKU, NODE, SUP).warnings())
                .anyMatch(w -> w.contains("forecast was generated"));
    }

    @Test
    @DisplayName("no forecast at all is not something to guess through")
    void noForecastThrows() {
        doReturn(List.of()).when(forecasts)
                .findByNodeIdAndSkuAndForecastDateBetweenOrderByForecastDate(
                        anyString(), anyString(), any(), any());

        assertThatThrownBy(() -> planner.plan(SKU, NODE, SUP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manual demand assumption");
    }

    @Test
    @DisplayName("unknown sku fails loudly")
    void unknownSku() {
        when(products.findById(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> planner.plan(SKU, NODE, SUP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown sku");
    }

    // ---- fixtures ---------------------------------------------------------

    void product(int casePack, int volume, int shelfLife, boolean perishable, String abc) {
        Product p = new Product();
        p.setSku(SKU);
        p.setName("Whole Milk 1L");
        p.setCategory("dairy");
        p.setUnitCost(new BigDecimal("0.95"));
        p.setCasePack(casePack);
        p.setUnitVolumeCm3(volume);
        p.setShelfLifeDays(shelfLife);
        p.setPerishable(perishable);
        p.setAbcClass(abc);
        when(products.findById(SKU)).thenReturn(Optional.of(p));
    }

    void node(int reviewDays, long capacity, long used) {
        Node n = new Node();
        n.setId(NODE);
        n.setReviewPeriodDays(reviewDays);
        n.setStorageCapacityCm3(capacity);
        n.setStorageUsedCm3(used);
        when(nodes.findById(NODE)).thenReturn(Optional.of(n));
    }

    void supplier(int leadTime, String leadStd, int moq, boolean active) {
        Supplier s = new Supplier();
        s.setId(SUP);
        s.setLeadTimeDays(leadTime);
        s.setLeadTimeStd(new BigDecimal(leadStd));
        s.setMoqUnits(moq);
        s.setActive(active);
        s.setReliabilityScore(new BigDecimal("0.94"));
        when(suppliers.findById(SUP)).thenReturn(Optional.of(s));
    }

    void supplierProduct(String price, int minOrder, int capacity) {
        SupplierProduct sp = new SupplierProduct();
        sp.setSupplierId(SUP);
        sp.setSku(SKU);
        sp.setUnitPrice(new BigDecimal(price));
        sp.setCurrency("USD");
        sp.setMinOrderUnits(minOrder);
        sp.setMaxDailyCapacity(capacity);
        when(supplierProducts.findById(any())).thenReturn(Optional.of(sp));
    }

    void inventory(int onHand, int reserved, int inTransit) {
        Inventory i = new Inventory();
        i.setNodeId(NODE);
        i.setSku(SKU);
        i.setOnHand(onHand);
        i.setReserved(reserved);
        i.setInTransit(inTransit);
        i.setUpdatedAt(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant());
        when(inventory.findByNodeIdAndSku(NODE, SKU)).thenReturn(Optional.of(i));
    }

    void forecast(int perDay, int std) {
        forecastRows(perDay, std, TODAY.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    void forecastGeneratedAt(LocalDate when) {
        forecastRows(55, 12, when.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    void forecastRows(int perDay, int std, Instant generatedAt) {
        List<DemandForecast> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            DemandForecast f = new DemandForecast();
            f.setNodeId(NODE);
            f.setSku(SKU);
            f.setForecastDate(TODAY.plusDays(i));
            f.setForecastUnits(BigDecimal.valueOf(perDay));
            f.setForecastStd(BigDecimal.valueOf(std));
            f.setModelVersion("v4");
            f.setGeneratedAt(generatedAt);
            rows.add(f);
        }
        // doAnswer, not when(...).thenAnswer - re-stubbing with when() invokes the
        // previously registered answer with null args first, which blows up.
        doAnswer(call -> {
            LocalDate from = call.getArgument(2);
            LocalDate to = call.getArgument(3);
            return rows.stream()
                    .filter(r -> !r.getForecastDate().isBefore(from)
                              && !r.getForecastDate().isAfter(to))
                    .toList();
        }).when(forecasts).findByNodeIdAndSkuAndForecastDateBetweenOrderByForecastDate(
                anyString(), anyString(), any(), any());
    }

    void budget(String allocated, String committed, String spent) {
        Budget b = new Budget();
        b.setNodeId(NODE);
        b.setCategory("dairy");
        b.setPeriod("2026-03");
        b.setAllocated(new BigDecimal(allocated));
        b.setCommitted(new BigDecimal(committed));
        b.setSpent(new BigDecimal(spent));
        when(budgets.findByNodeIdAndCategoryAndPeriod(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(b));
    }

    static PlanningProperties planningProps() {
        return new PlanningProperties(
                Map.of("A", 0.98, "B", 0.95, "C", 0.90),
                new PlanningProperties.Cover(30, 45),
                new PlanningProperties.Overbuy(1.25, 1.50),
                new PlanningProperties.PriceVariance(0.10, 0.25),
                new BigDecimal("1000.00"),
                new BigDecimal("0.50"),
                new PlanningProperties.Weights(0.35, 0.35, 0.20, 0.10));
    }
}
