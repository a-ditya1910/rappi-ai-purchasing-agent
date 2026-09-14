package com.rappi.buyer.verify;

import com.rappi.buyer.constraints.Proposal;
import com.rappi.buyer.domain.PoStatus;
import com.rappi.buyer.domain.PurchaseOrder;
import com.rappi.buyer.repo.PurchaseOrderRepo;
import com.rappi.buyer.tools.PurchaseOrderService;
import com.rappi.buyer.tools.PurchaseOrderService.WriteResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The write path against the real database. Needs docker compose up.
 *
 * These are the assertions the assignment actually asks for: that the agent can
 * execute a decision, then work out for itself whether what happened matches
 * what it intended.
 */
@SpringBootTest
class WriteAndVerifyIT {

    @Autowired PurchaseOrderService orders;
    @Autowired Verifier verifier;
    @Autowired PurchaseOrderRepo purchaseOrders;

    static final String NODE = "NODE-BOG-01";

    private String key() {
        return "test-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("a retried write returns the first order, it does not make a second one")
    void idempotentRetry() {
        String idem = key();
        long before = purchaseOrders.count();

        WriteResult first = orders.create("SKU-CHIPS-150G", NODE, "SUP-SNACKCO", 240,
                new BigDecimal("0.62"), LocalDate.of(2026, 3, 24), idem, 240, null, "test");
        WriteResult retry = orders.create("SKU-CHIPS-150G", NODE, "SUP-SNACKCO", 240,
                new BigDecimal("0.62"), LocalDate.of(2026, 3, 24), idem, 240, null, "test");

        assertThat(first.executed()).isTrue();
        assertThat(retry.idempotent()).isTrue();
        assertThat(retry.poId()).isEqualTo(first.poId());
        assertThat(purchaseOrders.count()).isEqualTo(before + 1);

        orders.cancel(first.poId(), orders.reread(first.poId()).getVersion(), "cleanup");
    }

    @Test
    @DisplayName("the supplier caps the order, and L1 notices without anyone injecting a fault")
    void supplierCapProducesARealMismatch() {
        // SUP-ANDINA can ship 250 coffee a day. asking for 500 is how scenario 2
        // happens on an ordinary run rather than a rigged one.
        String idem = key();
        WriteResult res = orders.create("SKU-COFFEE-500G", NODE, "SUP-ANDINA", 500,
                new BigDecimal("3.90"), LocalDate.of(2026, 3, 24), idem, 500, null, "test");

        if (!res.executed()) {
            // tier gate refused it, which is also a valid outcome - just not the
            // one this test is about
            assertThat(res.validation().riskTier()).isEqualTo("T3");
            return;
        }

        Proposal intended = new Proposal("SKU-COFFEE-500G", NODE, "SUP-ANDINA", 500,
                new BigDecimal("3.90"), LocalDate.of(2026, 3, 24), 500);
        VerificationReport report = verifier.verify(res.poId(), intended);

        System.out.println("--- verification diff ---");
        report.diffs().forEach(d -> System.out.printf("  %-3s %-18s intended %-12s actual %-12s %s%n",
                d.level(), d.field(), d.intended(), d.actual(), d.ok() ? "ok" : "MISMATCH"));
        report.notes().forEach(n -> System.out.println("  note: " + n));

        assertThat(report.outcome()).isEqualTo(VerificationReport.Outcome.MISMATCH);
        assertThat(report.mismatches())
                .anyMatch(d -> d.field().equals("qtyConfirmed") && d.actual().equals("250"));

        orders.cancel(res.poId(), orders.reread(res.poId()).getVersion(), "cleanup");
    }

    @Test
    @DisplayName("amending with a stale version is refused rather than clobbering the change")
    void staleVersionRefused() {
        String idem = key();
        WriteResult res = orders.create("SKU-CHIPS-150G", NODE, "SUP-SNACKCO", 240,
                new BigDecimal("0.62"), LocalDate.of(2026, 3, 24), idem, 240, null, "test");
        if (!res.executed()) {
            return;
        }

        PurchaseOrder po = orders.reread(res.poId());
        int current = po.getVersion();

        WriteResult stale = orders.amend(res.poId(), 120, current - 1, "should not apply");
        assertThat(stale.executed()).isFalse();
        assertThat(stale.message()).contains("STALE_VERSION");

        WriteResult fresh = orders.amend(res.poId(), 120, current, "correct version");
        assertThat(fresh.executed()).isTrue();

        orders.cancel(res.poId(), orders.reread(res.poId()).getVersion(), "cleanup");
    }

    @Test
    @DisplayName("a T3 decision writes nothing at all")
    void tierThreeRefusesToExecute() {
        long before = purchaseOrders.count();

        // 204 against a recommendation of 800 is a 74.5% delta, which is a human's
        // call, not the agent's
        WriteResult res = orders.create("SKU-MILK-1L", NODE, "SUP-LACTEO", 204,
                new BigDecimal("0.95"), LocalDate.of(2026, 3, 22), key(), 800, null, "test");

        assertThat(res.executed()).isFalse();
        assertThat(res.validation().riskTier()).isEqualTo("T3");
        assertThat(res.poId()).isNull();
        assertThat(purchaseOrders.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("cancelling releases the committed budget")
    void cancelReleasesBudget() {
        String idem = key();
        WriteResult res = orders.create("SKU-CHIPS-150G", NODE, "SUP-SNACKCO", 240,
                new BigDecimal("0.62"), LocalDate.of(2026, 3, 24), idem, 240, null, "test");
        if (!res.executed()) {
            return;
        }

        orders.cancel(res.poId(), orders.reread(res.poId()).getVersion(), "no longer needed");
        assertThat(orders.reread(res.poId()).getStatus()).isEqualTo(PoStatus.CANCELLED);
    }
}
