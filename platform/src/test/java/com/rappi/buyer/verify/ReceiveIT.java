package com.rappi.buyer.verify;

import com.rappi.buyer.domain.Budget;
import com.rappi.buyer.domain.Inventory;
import com.rappi.buyer.domain.Supplier;
import com.rappi.buyer.repo.BudgetRepo;
import com.rappi.buyer.repo.InventoryRepo;
import com.rappi.buyer.repo.SupplierRepo;
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
 * A chips delivery arriving, against the real database. SnackCo's lead time is
 * 6 days, so an order made on 2026-03-10 is promised for 2026-03-16. Every test
 * puts the stock, the money and the supplier's score back afterwards.
 */
@SpringBootTest
class ReceiveIT {

    @Autowired PurchaseOrderService orders;
    @Autowired InventoryRepo inventory;
    @Autowired BudgetRepo budgets;
    @Autowired SupplierRepo suppliers;

    static final String NODE = "NODE-BOG-01";
    static final String SKU = "SKU-CHIPS-150G";
    static final String SUP = "SUP-SNACKCO";

    String poId;
    int onHand, inTransit;
    BigDecimal committed, spent, reliability;

    @BeforeEach
    void freshOrder() {
        Inventory i = inv();
        onHand = i.getOnHand();
        inTransit = i.getInTransit();
        Budget b = budget();
        committed = b.getCommitted();
        spent = b.getSpent();
        reliability = supplier().getReliabilityScore();

        WriteResult res = orders.create(SKU, NODE, SUP, 240, new BigDecimal("0.62"),
                LocalDate.of(2026, 3, 16), "rcv-" + UUID.randomUUID(), 240, null, "test");
        assertThat(res.executed()).as(res.message()).isTrue();
        poId = res.poId();
    }

    @AfterEach
    void putEverythingBack() {
        Inventory i = inv();
        i.setOnHand(onHand);
        i.setInTransit(inTransit);
        inventory.save(i);
        Budget b = budget();
        b.setCommitted(committed);
        b.setSpent(spent);
        budgets.save(b);
        Supplier s = supplier();
        s.setReliabilityScore(reliability);
        suppliers.save(s);
    }

    Inventory inv() { return inventory.findByNodeIdAndSku(NODE, SKU).orElseThrow(); }
    Budget budget() { return budgets.findByNodeIdAndCategoryAndPeriod(NODE, "snacks", "2026-03").orElseThrow(); }
    Supplier supplier() { return suppliers.findById(SUP).orElseThrow(); }

    @Test
    @DisplayName("an on time, complete delivery moves the stock and the money, and lifts the score")
    void completeOnTime() {
        Map<String, Object> out = orders.receive(poId, 240, LocalDate.of(2026, 3, 15));

        assertThat(out.get("onTime")).isEqualTo(true);
        assertThat(inv().getOnHand()).isEqualTo(onHand + 240);
        assertThat(inv().getInTransit()).isEqualTo(inTransit);              // up 240 on create, down 240 now
        assertThat(budget().getCommitted()).isEqualByComparingTo(committed);
        assertThat(budget().getSpent()).isEqualByComparingTo(spent.add(new BigDecimal("148.80")));
        assertThat(supplier().getReliabilityScore())
                .isEqualByComparingTo(PurchaseOrderService.nextReliability(reliability, 1.0, true));
    }

    @Test
    @DisplayName("half of it, four days late - the score drops and only what arrived is paid for")
    void shortAndLate() {
        Map<String, Object> out = orders.receive(poId, 120, LocalDate.of(2026, 3, 20));

        assertThat(out.get("onTime")).isEqualTo(false);
        assertThat(out.get("fillRate")).isEqualTo(0.5);
        assertThat(inv().getOnHand()).isEqualTo(onHand + 120);
        assertThat(budget().getSpent()).isEqualByComparingTo(spent.add(new BigDecimal("74.40")));
        assertThat(supplier().getReliabilityScore()).isLessThan(reliability);
    }

    @Test
    @DisplayName("receiving the same order twice is refused")
    void onlyOnce() {
        orders.receive(poId, 240, LocalDate.of(2026, 3, 15));
        assertThat(orders.receive(poId, 240, LocalDate.of(2026, 3, 15)).get("error")).isEqualTo("PO_CLOSED");
    }
}
