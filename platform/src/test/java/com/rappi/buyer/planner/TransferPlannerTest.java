package com.rappi.buyer.planner;

import com.rappi.buyer.config.PlanningProperties;
import com.rappi.buyer.domain.DemandForecast;
import com.rappi.buyer.domain.Inventory;
import com.rappi.buyer.domain.Node;
import com.rappi.buyer.domain.Product;
import com.rappi.buyer.repo.ForecastRepo;
import com.rappi.buyer.repo.InventoryRepo;
import com.rappi.buyer.repo.NodeRepo;
import com.rappi.buyer.repo.ProductRepo;
import com.rappi.buyer.repo.PurchaseOrderRepo;
import com.rappi.buyer.repo.TransferOrderRepo;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Rice, the seeded case: Chapinero needs it and cannot buy it, Usaquen has 1400.
 * The numbers are worked by hand in the comments so a change in the maths shows.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferPlannerTest {

    static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    static final String SKU = "SKU-RICE-5KG";
    static final String TO = "NODE-BOG-01";
    static final String FROM = "NODE-BOG-02";

    @Mock ProductRepo products;
    @Mock NodeRepo nodes;
    @Mock InventoryRepo inventory;
    @Mock ForecastRepo forecasts;
    @Mock PurchaseOrderRepo purchaseOrders;
    @Mock TransferOrderRepo transfers;

    TransferPlanner planner;
    List<Inventory> stock = new ArrayList<>();

    @BeforeEach
    void setUp() {
        PlanningProperties props = new PlanningProperties(
                Map.of("A", 0.98, "B", 0.95, "C", 0.90),
                new PlanningProperties.Cover(30, 45),
                new PlanningProperties.Overbuy(1.25, 1.50),
                new PlanningProperties.PriceVariance(0.10, 0.25),
                new BigDecimal("1000.00"), new BigDecimal("0.50"),
                new PlanningProperties.Weights(0.35, 0.35, 0.20, 0.10));
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        planner = new TransferPlanner(products, nodes, inventory, forecasts, purchaseOrders,
                transfers, props, clock, 1);

        Product p = new Product();
        p.setSku(SKU);
        p.setCasePack(4);
        p.setUnitVolumeCm3(5200);
        p.setAbcClass("C");                                  // z = 1.28
        when(products.findById(SKU)).thenReturn(Optional.of(p));
        when(inventory.findBySku(SKU)).thenReturn(stock);

        node(TO, 40_000_000L, 34_000_000L);                  // 6M cm3 free -> 1152 units
        node(FROM, 40_000_000L, 21_000_000L);
        stock(TO, 90, 10);
        forecast(TO, 30, 8);
        forecast(FROM, 12, 3);
    }

    @Test
    @DisplayName("rice: chapinero needs 189, usaquen can spare plenty, 188 goes in whole cases")
    void riceFromUsaquen() {
        stock(FROM, 1400, 0);

        TransferPlanner.TransferPlan plan = planner.plan(SKU, TO);

        // horizon 1 + 7 = 8 days. position 90 - 10 = 80. demand 8 x 30 = 240.
        // safety stock ceil(1.28 x sqrt(8 x 8^2)) = ceil(28.96) = 29. target 269, need 189
        assertThat(plan.horizonDays()).isEqualTo(8);
        assertThat(plan.position()).isEqualTo(80);
        assertThat(plan.need()).isEqualTo(189);

        TransferPlanner.Option o = plan.options().get(0);
        // usaquen keeps 7 x 12 = 84 + ceil(1.28 x sqrt(7 x 3^2)) = 11, so 95
        assertThat(o.keep()).isEqualTo(95);
        assertThat(o.spare()).isEqualTo(1305);
        assertThat(o.qty()).isEqualTo(188);                  // 189 rounded DOWN to cases of 4
        assertThat(o.usable()).isTrue();
    }

    @Test
    @DisplayName("a store only gives away what is above its own safety stock")
    void senderKeepsItsSafetyStock() {
        stock(FROM, 135, 0);                                 // 135 - 95 = 40 spare
        TransferPlanner.Option o = planner.plan(SKU, TO).options().get(0);
        assertThat(o.spare()).isEqualTo(40);
        assertThat(o.qty()).isEqualTo(40);
    }

    @Test
    @DisplayName("nothing spare means no transfer, not a transfer that empties the sender")
    void nothingSpare() {
        stock(FROM, 90, 0);
        TransferPlanner.Option o = planner.plan(SKU, TO).options().get(0);
        assertThat(o.usable()).isFalse();
        assertThat(o.note()).contains("nothing spare");
    }

    @Test
    @DisplayName("the receiver's free storage caps the transfer")
    void storageCaps() {
        node(TO, 40_000_000L, 39_500_000L);                  // 500k cm3 -> 96 units
        stock(FROM, 1400, 0);
        assertThat(planner.plan(SKU, TO).options().get(0).qty()).isEqualTo(96);
    }

    @Test
    @DisplayName("a store with no forecast cannot be a sender - what it must keep is unknown")
    void noForecastNoTransfer() {
        node("NODE-MEX-01", 35_000_000L, 20_000_000L);
        stock("NODE-MEX-01", 500, 0);
        TransferPlanner.Option mex = planner.plan(SKU, TO).options().stream()
                .filter(x -> x.fromNode().equals("NODE-MEX-01")).findFirst().orElseThrow();
        assertThat(mex.usable()).isFalse();
        assertThat(mex.note()).contains("no forecast");
    }

    // ---- fixtures ----------------------------------------------------------

    void node(String id, long capacity, long used) {
        Node n = new Node();
        n.setId(id);
        n.setReviewPeriodDays(7);
        n.setStorageCapacityCm3(capacity);
        n.setStorageUsedCm3(used);
        when(nodes.findById(id)).thenReturn(Optional.of(n));
    }

    void stock(String node, int onHand, int reserved) {
        Inventory i = new Inventory();
        i.setNodeId(node);
        i.setSku(SKU);
        i.setOnHand(onHand);
        i.setReserved(reserved);
        stock.removeIf(x -> x.getNodeId().equals(node));
        stock.add(i);
        when(inventory.findByNodeIdAndSku(node, SKU)).thenReturn(Optional.of(i));
    }

    void forecast(String node, int perDay, int std) {
        List<DemandForecast> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            DemandForecast f = new DemandForecast();
            f.setNodeId(node);
            f.setSku(SKU);
            f.setForecastDate(TODAY.plusDays(i));
            f.setForecastUnits(BigDecimal.valueOf(perDay));
            f.setForecastStd(BigDecimal.valueOf(std));
            rows.add(f);
        }
        doAnswer(call -> {
            LocalDate from = call.getArgument(2);
            LocalDate to = call.getArgument(3);
            return rows.stream().filter(r -> !r.getForecastDate().isBefore(from)
                    && !r.getForecastDate().isAfter(to)).toList();
        }).when(forecasts).findByNodeIdAndSkuAndForecastDateBetweenOrderByForecastDate(
                eq(node), eq(SKU), any(), any());
    }
}
