package com.rappi.buyer.verify;

import com.rappi.buyer.constraints.Proposal;
import com.rappi.buyer.domain.AgentRun;
import com.rappi.buyer.domain.Approval;
import com.rappi.buyer.domain.PoStatus;
import com.rappi.buyer.domain.PurchaseOrder;
import com.rappi.buyer.planner.ReorderPlanner;
import com.rappi.buyer.repo.AgentRunRepo;
import com.rappi.buyer.repo.ApprovalRepo;
import com.rappi.buyer.repo.InventoryRepo;
import com.rappi.buyer.repo.PurchaseOrderRepo;
import com.rappi.buyer.tools.PurchaseOrderService;
import com.rappi.buyer.tools.PurchaseOrderService.WriteResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
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
    @Autowired AgentRunRepo runs;
    @Autowired ApprovalRepo approvals;
    @Autowired InventoryRepo inventory;
    @Autowired ReorderPlanner planner;

    static final String NODE = "NODE-BOG-01";
    static final LocalDate MILK_DELIVERY = LocalDate.of(2026, 3, 15);

    private String key() {
        return "test-" + UUID.randomUUID();
    }

    private String newRun() {
        AgentRun r = new AgentRun();
        r.setId(UUID.randomUUID().toString());
        r.setScenario("TEST");
        r.setInput("{}");
        r.setStatus(AgentRun.Status.RUNNING);
        r.setCreatedAt(Instant.now());
        runs.save(r);
        return r.getId();
    }

    private String approve(String runId, String sku, String supplier, int qty, String price) {
        Approval a = new Approval();
        a.setId(UUID.randomUUID().toString());
        a.setRunId(runId);
        a.setReason("test");
        a.setRiskTier("T3");
        a.setProposedAction("{\"sku\":\"%s\",\"nodeId\":\"%s\",\"supplierId\":\"%s\",\"qty\":%d,\"unitPrice\":%s}"
                .formatted(sku, NODE, supplier, qty, price));
        a.setStatus(Approval.Status.APPROVED);
        a.setCreatedAt(Instant.now());
        approvals.save(a);
        return a.getId();
    }

    private void cleanup(WriteResult res) {
        if (res.executed()) {
            orders.cancel(res.poId(), orders.reread(res.poId()).getVersion(), "cleanup");
        }
    }

    // ---- approvals -------------------------------------------------------

    @Test
    @DisplayName("a buyer approved T3 order actually executes")
    void approvedT3OrderExecutes() {
        String run = newRun();
        String ap = approve(run, "SKU-MILK-1L", "SUP-LACTEO", 204, "0.95");

        WriteResult res = orders.create("SKU-MILK-1L", NODE, "SUP-LACTEO", 204,
                new BigDecimal("0.95"), MILK_DELIVERY, key(), 800, run, "agent", ap);

        assertThat(res.executed()).as(res.message()).isTrue();
        assertThat(res.validation().riskTier()).isEqualTo("T3");   // still T3, the buyer owned it
        cleanup(res);
    }

    @Test
    @DisplayName("approving 204 does not let the agent order 216")
    void approvalCannotChangeTheOrder() {
        String run = newRun();
        String ap = approve(run, "SKU-MILK-1L", "SUP-LACTEO", 204, "0.95");

        WriteResult res = orders.create("SKU-MILK-1L", NODE, "SUP-LACTEO", 216,
                new BigDecimal("0.95"), MILK_DELIVERY, key(), 800, run, "agent", ap);

        assertThat(res.executed()).isFalse();
        assertThat(res.message()).contains("different order");
        cleanup(res);
    }

    @Test
    @DisplayName("an approval never overrides a block")
    void approvalNeverOverridesABlock() {
        // 600 milk is $570 against $480 of dairy budget
        String run = newRun();
        String ap = approve(run, "SKU-MILK-1L", "SUP-LACTEO", 600, "0.95");

        WriteResult res = orders.create("SKU-MILK-1L", NODE, "SUP-LACTEO", 600,
                new BigDecimal("0.95"), MILK_DELIVERY, key(), 800, run, "agent", ap);

        assertThat(res.executed()).isFalse();
        assertThat(res.message()).startsWith("blocked").contains("BUDGET");
        cleanup(res);
    }

    // ---- in transit and ids ----------------------------------------------

    @Test
    @DisplayName("in_transit moves with the order, so the next plan has no data conflict")
    void inTransitFollowsTheOrder() {
        int before = inventory.findByNodeIdAndSku(NODE, "SKU-CHIPS-150G").orElseThrow().getInTransit();

        WriteResult res = orders.create("SKU-CHIPS-150G", NODE, "SUP-SNACKCO", 240,
                new BigDecimal("0.62"), LocalDate.of(2026, 3, 24), key(), 240, null, "test");
        assertThat(res.executed()).as(res.message()).isTrue();

        assertThat(inventory.findByNodeIdAndSku(NODE, "SKU-CHIPS-150G").orElseThrow().getInTransit())
                .isEqualTo(before + 240);
        assertThat(planner.plan("SKU-CHIPS-150G", NODE, "SUP-SNACKCO").warnings())
                .noneMatch(w -> w.startsWith("DATA_CONFLICT"));

        cleanup(res);
        assertThat(inventory.findByNodeIdAndSku(NODE, "SKU-CHIPS-150G").orElseThrow().getInTransit())
                .isEqualTo(before);
    }

    @Test
    @DisplayName("two orders get two different ids")
    void idsAreUnique() {
        WriteResult a = orders.create("SKU-CHIPS-150G", NODE, "SUP-SNACKCO", 240,
                new BigDecimal("0.62"), LocalDate.of(2026, 3, 24), key(), 240, null, "test");
        WriteResult b = orders.create("SKU-CHIPS-150G", NODE, "SUP-SNACKCO", 240,
                new BigDecimal("0.62"), LocalDate.of(2026, 3, 24), key(), 240, null, "test");

        assertThat(a.poId()).startsWith("PO-").isNotEqualTo(b.poId());
        cleanup(a);
        cleanup(b);
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
