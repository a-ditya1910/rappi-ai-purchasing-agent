package com.rappi.buyer.constraints;

import com.rappi.buyer.config.PlanningProperties;
import com.rappi.buyer.constraints.ValidationReport.Status;
import com.rappi.buyer.constraints.ValidationReport.Verdict;
import com.rappi.buyer.domain.Budget;
import com.rappi.buyer.domain.Node;
import com.rappi.buyer.domain.PoStatus;
import com.rappi.buyer.domain.Product;
import com.rappi.buyer.domain.PurchaseOrder;
import com.rappi.buyer.domain.Supplier;
import com.rappi.buyer.domain.SupplierProduct;
import com.rappi.buyer.planner.ReorderPlan;
import com.rappi.buyer.repo.BudgetRepo;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConstraintEngineTest {

    static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    static final String SKU = "SKU-MILK-1L";
    static final String NODE = "NODE-BOG-01";
    static final String SUP = "SUP-LACTEO";

    @Mock ProductRepo products;
    @Mock NodeRepo nodes;
    @Mock SupplierRepo suppliers;
    @Mock SupplierProductRepo supplierProducts;
    @Mock BudgetRepo budgets;
    @Mock PurchaseOrderRepo purchaseOrders;

    ConstraintEngine engine;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        engine = new ConstraintEngine(products, nodes, suppliers, supplierProducts, budgets,
                purchaseOrders, ReorderPlannerProps.planningProps(), clock);

        product(12, 1100, 12, true, "A");
        node(40_000_000L, 34_000_000L);
        supplier(200, true, "0.94");
        supplierProduct("0.95", 200, 4000);
        budget("2500", "1620", "400");                       // 480 available
        when(purchaseOrders.lastPaidPrices(anyString(), anyString())).thenReturn(List.of());
        when(purchaseOrders.findOpenForSku(anyString(), anyString())).thenReturn(List.of());
    }

    // ---- the flagship case -------------------------------------------------

    @Test
    @DisplayName("milk at 204 passes, but the shelf life and qty delta push it to a human")
    void milkNeedsApproval() {
        ValidationReport r = engine.validate(proposal(204, "0.95", 800), plan(680, "57.5", 12, 5));

        assertThat(r.blocking()).isEmpty();
        assertThat(status(r, "BUDGET")).isEqualTo(Status.PASS);
        assertThat(status(r, "MOQ")).isEqualTo(Status.PASS);
        assertThat(status(r, "CASE_PACK")).isEqualTo(Status.PASS);
        assertThat(status(r, "SHELF_LIFE")).isEqualTo(Status.WARN);      // 15.4d cover on 12d product
        assertThat(status(r, "QTY_DELTA")).isEqualTo(Status.APPROVAL);   // 204 vs 800 is -74.5%
        assertThat(r.verdict()).isEqualTo(Verdict.NEEDS_APPROVAL);
        assertThat(r.riskTier()).isEqualTo("T3");
        assertThat(r.requiresApproval()).isTrue();
    }

    @Test
    @DisplayName("same order without a wild recommendation is auto approvable")
    void modestOrderIsT2() {
        product(12, 1100, 365, false, "A");                  // not perishable
        ValidationReport r = engine.validate(proposal(204, "0.95", 200), plan(680, "57.5", 12, 5));

        assertThat(r.riskTier()).isEqualTo("T2");
        assertThat(r.ok()).isTrue();
        assertThat(r.requiresApproval()).isFalse();
    }

    // ---- blocks ------------------------------------------------------------

    @Test
    @DisplayName("over budget blocks")
    void overBudgetBlocks() {
        ValidationReport r = engine.validate(proposal(1000, "0.95", null), plan(680, "57.5", 12, 5));
        assertThat(status(r, "BUDGET")).isEqualTo(Status.BLOCK);
        assertThat(r.verdict()).isEqualTo(Verdict.BLOCKED);
        assertThat(r.riskTier()).isEqualTo("T3");
    }

    @Test
    @DisplayName("below moq blocks")
    void belowMoqBlocks() {
        ValidationReport r = engine.validate(proposal(48, "0.95", null), plan(680, "57.5", 12, 5));
        assertThat(status(r, "MOQ")).isEqualTo(Status.BLOCK);
    }

    @Test
    @DisplayName("quantity that is not a case pack multiple blocks")
    void casePackBlocks() {
        ValidationReport r = engine.validate(proposal(205, "0.95", null), plan(680, "57.5", 12, 5));
        assertThat(status(r, "CASE_PACK")).isEqualTo(Status.BLOCK);
    }

    @Test
    @DisplayName("inactive supplier blocks and forces T3")
    void inactiveSupplierBlocks() {
        supplier(200, false, "0.94");
        ValidationReport r = engine.validate(proposal(204, "0.95", null), plan(680, "57.5", 12, 5));
        assertThat(status(r, "SUPPLIER_ACTIVE")).isEqualTo(Status.BLOCK);
        assertThat(r.riskTier()).isEqualTo("T3");
    }

    @Test
    @DisplayName("unreliable supplier blocks below 0.70, warns below 0.85")
    void reliabilityBands() {
        supplier(200, true, "0.60");
        assertThat(status(engine.validate(proposal(204, "0.95", null), plan(680, "57.5", 12, 5)),
                "SUPPLIER_RELIABILITY")).isEqualTo(Status.BLOCK);

        supplier(200, true, "0.80");
        assertThat(status(engine.validate(proposal(204, "0.95", null), plan(680, "57.5", 12, 5)),
                "SUPPLIER_RELIABILITY")).isEqualTo(Status.WARN);
    }

    @Test
    @DisplayName("storage that cannot hold the order blocks")
    void storageBlocks() {
        node(40_000_000L, 39_900_000L);                      // 100k cm3 free, 204 units need 224k
        ValidationReport r = engine.validate(proposal(204, "0.95", null), plan(680, "57.5", 12, 5));
        assertThat(status(r, "STORAGE")).isEqualTo(Status.BLOCK);
    }

    @Test
    @DisplayName("an open PO covering the same window blocks a duplicate")
    void duplicatePoBlocks() {
        PurchaseOrder open = new PurchaseOrder();
        open.setId("PO-0007");
        open.setExpectedDelivery(TODAY.plusDays(3));
        open.setStatus(PoStatus.CONFIRMED);
        when(purchaseOrders.findOpenForSku(anyString(), anyString())).thenReturn(List.of(open));

        ValidationReport r = engine.validate(proposal(204, "0.95", null), plan(680, "57.5", 12, 5));

        assertThat(status(r, "DUPLICATE_PO")).isEqualTo(Status.BLOCK);
        assertThat(r.blocking()).anyMatch(b -> b.contains("PO-0007"));
    }

    @Test
    @DisplayName("a data conflict from the planner becomes a block, not a warning")
    void dataConflictBlocks() {
        ReorderPlan p = planWithWarning("DATA_CONFLICT: in_transit is 400 but open POs sum to 700");
        ValidationReport r = engine.validate(proposal(204, "0.95", null), p);
        assertThat(status(r, "DATA_CONFLICT")).isEqualTo(Status.BLOCK);
        assertThat(r.verdict()).isEqualTo(Verdict.BLOCKED);
    }

    @Test
    @DisplayName("negative quantity never reaches the database")
    void sanityBlocks() {
        ValidationReport r = engine.validate(proposal(-50, "0.95", null), plan(680, "57.5", 12, 5));
        assertThat(status(r, "SANITY")).isEqualTo(Status.BLOCK);
    }

    // ---- approvals ---------------------------------------------------------

    @Test
    @DisplayName("price more than 10% over the last paid needs approval")
    void priceVarianceNeedsApproval() {
        when(purchaseOrders.lastPaidPrices(anyString(), anyString()))
                .thenReturn(List.of(new BigDecimal("3.90")));
        supplierProduct("4.40", 100, 4000);
        supplier(100, true, "0.91");

        ValidationReport r = engine.validate(proposal(250, "4.40", null), plan(410, "42.0", 13, 9));

        assertThat(status(r, "PRICE_VARIANCE")).isEqualTo(Status.APPROVAL);
        assertThat(r.riskTier()).isEqualTo("T3");
    }

    @Test
    @DisplayName("price more than 25% over the last paid is not allowed at all")
    void priceVarianceBlocks() {
        when(purchaseOrders.lastPaidPrices(anyString(), anyString()))
                .thenReturn(List.of(new BigDecimal("0.95")));
        ValidationReport r = engine.validate(proposal(204, "1.50", null), plan(680, "57.5", 12, 5));
        assertThat(status(r, "PRICE_VARIANCE")).isEqualTo(Status.BLOCK);
    }

    @Test
    @DisplayName("value over the threshold needs approval even when everything else is clean")
    void valueThresholdNeedsApproval() {
        product(12, 1100, 365, false, "A");
        budget("99999", "0", "0");
        node(900_000_000L, 0L);
        supplierProduct("0.95", 200, 99999);

        ValidationReport r = engine.validate(proposal(2400, "0.95", 2400), plan(680, "57.5", 12, 5));

        assertThat(status(r, "VALUE_THRESHOLD")).isEqualTo(Status.APPROVAL);
        assertThat(r.riskTier()).isEqualTo("T3");
    }

    // ---- warnings ----------------------------------------------------------

    @Test
    @DisplayName("exceeding supplier daily capacity warns rather than blocks")
    void capacityWarns() {
        // non perishable, otherwise 504 units trips the shelf life block and we end
        // up testing the wrong rule
        product(12, 1100, 365, false, "A");
        supplierProduct("0.95", 200, 250);

        ValidationReport r = engine.validate(proposal(504, "0.95", null), plan(680, "57.5", 12, 5));

        assertThat(status(r, "MAX_CAPACITY")).isEqualTo(Status.WARN);
        assertThat(r.blocking()).isEmpty();
    }

    @Test
    @DisplayName("an unavoidable stockout is surfaced, not hidden")
    void leadTimeInfeasibleWarns() {
        ValidationReport r = engine.validate(proposal(204, "0.95", null), plan(100, "57.5", 12, 5));
        assertThat(status(r, "LEAD_TIME_FEASIBLE")).isEqualTo(Status.WARN);
    }

    // ---- fixtures ----------------------------------------------------------

    Status status(ValidationReport r, String id) {
        return r.checks().stream().filter(c -> c.id().equals(id)).findFirst()
                .map(ValidationReport.Check::status)
                .orElseThrow(() -> new AssertionError("no check called " + id));
    }

    Proposal proposal(int qty, String price, Integer recommended) {
        return new Proposal(SKU, NODE, SUP, qty, new BigDecimal(price),
                TODAY.plusDays(5), recommended);
    }

    ReorderPlan plan(int position, String meanDaily, int protection, int leadTime) {
        return buildPlan(position, meanDaily, protection, leadTime, List.of());
    }

    ReorderPlan planWithWarning(String warning) {
        return buildPlan(680, "57.5", 12, 5, List.of(warning));
    }

    ReorderPlan buildPlan(int position, String meanDaily, int protection, int leadTime,
                          List<String> warnings) {
        BigDecimal mean = new BigDecimal(meanDaily);
        BigDecimal coverNow = BigDecimal.valueOf(position)
                .divide(mean, 1, java.math.RoundingMode.HALF_UP);
        return new ReorderPlan(SKU, NODE, SUP,
                320, 40, 400, position,
                leadTime, protection - leadTime, protection,
                mean.multiply(BigDecimal.valueOf(protection)), mean,
                146, 0.98, 2.05,
                836, 156, 200, 204, 505, 5454, 204,
                coverNow, coverNow, new BigDecimal("0.95"), new BigDecimal("193.80"),
                List.of(), warnings);
    }

    void product(int casePack, int volume, int shelfLife, boolean perishable, String abc) {
        Product p = new Product();
        p.setSku(SKU);
        p.setCategory("dairy");
        p.setUnitCost(new BigDecimal("0.95"));
        p.setCasePack(casePack);
        p.setUnitVolumeCm3(volume);
        p.setShelfLifeDays(shelfLife);
        p.setPerishable(perishable);
        p.setAbcClass(abc);
        when(products.findById(SKU)).thenReturn(Optional.of(p));
    }

    void node(long capacity, long used) {
        Node n = new Node();
        n.setId(NODE);
        n.setReviewPeriodDays(7);
        n.setStorageCapacityCm3(capacity);
        n.setStorageUsedCm3(used);
        when(nodes.findById(NODE)).thenReturn(Optional.of(n));
    }

    void supplier(int moq, boolean active, String reliability) {
        Supplier s = new Supplier();
        s.setId(SUP);
        s.setLeadTimeDays(5);
        s.setLeadTimeStd(BigDecimal.ONE);
        s.setMoqUnits(moq);
        s.setActive(active);
        s.setReliabilityScore(new BigDecimal(reliability));
        when(suppliers.findById(SUP)).thenReturn(Optional.of(s));
    }

    void supplierProduct(String price, int minOrder, int capacity) {
        SupplierProduct sp = new SupplierProduct();
        sp.setSupplierId(SUP);
        sp.setSku(SKU);
        sp.setUnitPrice(new BigDecimal(price));
        sp.setMinOrderUnits(minOrder);
        sp.setMaxDailyCapacity(capacity);
        when(supplierProducts.findById(any())).thenReturn(Optional.of(sp));
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

    /** Shared with the planner test so the thresholds cannot drift between them. */
    static final class ReorderPlannerProps {
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
}
