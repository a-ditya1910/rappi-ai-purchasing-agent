package com.rappi.buyer.verify;

import com.rappi.buyer.domain.PoStatus;
import com.rappi.buyer.repo.InventoryRepo;
import com.rappi.buyer.repo.SupplierEventRepo;
import com.rappi.buyer.tools.PurchaseOrderService;
import com.rappi.buyer.tools.PurchaseOrderService.WriteResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A supplier email, as the agent read it, hitting the real database. Each test
 * works on a fresh chips order so the seeded POs are never touched.
 */
@SpringBootTest
class SupplierEventIT {

    @Autowired PurchaseOrderService orders;
    @Autowired InventoryRepo inventory;
    @Autowired SupplierEventRepo events;

    static final String NODE = "NODE-BOG-01";
    static final String SKU = "SKU-CHIPS-150G";
    static final String EMAIL = "We can only ship 120 of the 240 chips this week. Regards, SnackCo";

    String poId;
    int inTransitBefore;

    @BeforeEach
    void freshOrder() {
        inTransitBefore = inTransit();
        WriteResult res = orders.create(SKU, NODE, "SUP-SNACKCO", 240, new BigDecimal("0.62"),
                LocalDate.of(2026, 3, 24), "evt-" + UUID.randomUUID(), 240, null, "test");
        assertThat(res.executed()).as(res.message()).isTrue();
        poId = res.poId();
    }

    @AfterEach
    void cleanup() {
        orders.cancel(poId, orders.reread(poId).getVersion(), "cleanup");
        assertThat(inTransit()).isEqualTo(inTransitBefore);
    }

    int inTransit() {
        return inventory.findByNodeIdAndSku(NODE, SKU).orElseThrow().getInTransit();
    }

    Map<String, Object> send(String sender, Integer qty, String price, String text) {
        return orders.applySupplierEvent(poId, sender, "PARTIAL", qty, null,
                price == null ? null : new BigDecimal(price), text, null);
    }

    @Test
    @DisplayName("a short shipment email changes the order, in_transit and the budget together")
    void partialIsApplied() {
        Map<String, Object> out = send("SUP-SNACKCO", 120, null, EMAIL + UUID.randomUUID());

        assertThat(out.get("applied")).isEqualTo(true);
        assertThat(out.get("shortfall")).isEqualTo(120);
        assertThat(orders.reread(poId).getStatus()).isEqualTo(PoStatus.PARTIALLY_CONFIRMED);
        assertThat(orders.reread(poId).getLines().get(0).getQtyConfirmed()).isEqualTo(120);
        assertThat(orders.reread(poId).getTotalValue()).isEqualByComparingTo("74.40");
        assertThat(inTransit()).isEqualTo(inTransitBefore + 120);
    }

    @Test
    @DisplayName("the same email twice is applied once")
    void resendIsADuplicate() {
        String email = EMAIL + UUID.randomUUID();
        send("SUP-SNACKCO", 120, null, email);
        int version = orders.reread(poId).getVersion();

        Map<String, Object> again = send("SUP-SNACKCO", 120, null, email);

        assertThat(again.get("duplicate")).isEqualTo(true);
        assertThat(orders.reread(poId).getVersion()).isEqualTo(version);
    }

    @Test
    @DisplayName("a message about somebody else's order is refused and kept for audit")
    void wrongSenderIsRefused() {
        Map<String, Object> out = send("SUP-ANDINA", 120, null, EMAIL + UUID.randomUUID());

        assertThat(out.get("error")).isEqualTo("SENDER_MISMATCH");
        assertThat(orders.reread(poId).getLines().get(0).getQtyConfirmed()).isEqualTo(240);
        assertThat(events.findAll()).anyMatch(e -> poId.equals(e.getPoId()) && !e.isApplied());
    }

    @Test
    @DisplayName("a delivery date in the past is refused - '14 March' read as last year")
    void pastDateIsRefused() {
        Map<String, Object> out = orders.applySupplierEvent(poId, "SUP-SNACKCO", "PARTIAL", 120,
                LocalDate.of(2025, 3, 14), null, EMAIL + UUID.randomUUID(), null);
        assertThat(out.get("error")).isEqualTo("DATE_IN_PAST");
        assertThat(orders.reread(poId).getLines().get(0).getQtyConfirmed()).isEqualTo(240);
    }

    @Test
    @DisplayName("confirming more than was ordered is refused")
    void tooManyIsRefused() {
        assertThat(send("SUP-SNACKCO", 900, null, EMAIL + UUID.randomUUID()).get("error"))
                .isEqualTo("QTY_OUT_OF_RANGE");
    }

    @Test
    @DisplayName("a price 60% above what was ordered is not believed")
    void wildPriceIsRefused() {
        assertThat(send("SUP-SNACKCO", 120, "0.99", EMAIL + UUID.randomUUID()).get("error"))
                .isEqualTo("PRICE_SUSPECT");
    }
}
