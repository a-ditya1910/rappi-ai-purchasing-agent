package com.rappi.buyer.verify;

import com.rappi.buyer.api.PoController;
import com.rappi.buyer.domain.Budget;
import com.rappi.buyer.domain.Inventory;
import com.rappi.buyer.repo.BudgetRepo;
import com.rappi.buyer.repo.InventoryRepo;
import com.rappi.buyer.tools.PurchaseOrderService;
import com.rappi.buyer.tools.PurchaseOrderService.WriteResult;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two console actions that move the position down: selling stock and a
 * buyer cancelling an order. Chips, against the real database, put back after.
 */
@SpringBootTest
class BuyerActionsIT {

    @Autowired PoController controller;
    @Autowired PurchaseOrderService orders;
    @Autowired InventoryRepo inventory;
    @Autowired BudgetRepo budgets;

    static final String NODE = "NODE-BOG-01";
    static final String SKU = "SKU-CHIPS-150G";

    @Test
    void sellingTakesStockOffTheShelfButNeverReservedStock() {
        int before = inv().getOnHand();
        try {
            var ok = controller.sell(new PoController.Sell(SKU, NODE, 50));
            assertThat(ok.getStatusCode().value()).isEqualTo(200);
            assertThat(inv().getOnHand()).isEqualTo(before - 50);

            // more than on hand minus reserved is refused, nothing changes
            var tooMuch = controller.sell(new PoController.Sell(SKU, NODE, inv().available() + 1));
            assertThat(tooMuch.getStatusCode().value()).isEqualTo(409);
            assertThat(inv().getOnHand()).isEqualTo(before - 50);
        } finally {
            Inventory i = inv();
            i.setOnHand(before);
            inventory.save(i);
        }
    }

    @Test
    void cancellingGivesBackInTransitAndBudgetOnce() {
        int inTransit = inv().getInTransit();
        BigDecimal committed = budget().getCommitted();

        WriteResult res = orders.create(SKU, NODE, "SUP-SNACKCO", 240, new BigDecimal("0.62"),
                LocalDate.of(2026, 3, 16), "cancel-" + UUID.randomUUID(), 240, null, "test");
        assertThat(res.executed()).as(res.message()).isTrue();
        assertThat(inv().getInTransit()).isEqualTo(inTransit + 240);

        var ok = controller.cancelPo(res.poId());
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(inv().getInTransit()).isEqualTo(inTransit);
        assertThat(budget().getCommitted()).isEqualByComparingTo(committed);

        // a second click must not release the budget again
        assertThat(controller.cancelPo(res.poId()).getStatusCode().value()).isEqualTo(409);
        assertThat(budget().getCommitted()).isEqualByComparingTo(committed);
    }

    Inventory inv() {
        return inventory.findByNodeIdAndSku(NODE, SKU).orElseThrow();
    }

    Budget budget() {
        return budgets.findByNodeIdAndCategoryAndPeriod(NODE, "snacks", "2026-03").orElseThrow();
    }
}
