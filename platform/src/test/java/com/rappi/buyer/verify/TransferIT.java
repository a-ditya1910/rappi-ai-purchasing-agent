package com.rappi.buyer.verify;

import com.rappi.buyer.domain.AgentRun;
import com.rappi.buyer.domain.Approval;
import com.rappi.buyer.domain.Inventory;
import com.rappi.buyer.domain.TransferOrder;
import com.rappi.buyer.planner.ReorderPlanner;
import com.rappi.buyer.repo.AgentRunRepo;
import com.rappi.buyer.repo.ApprovalRepo;
import com.rappi.buyer.repo.InventoryRepo;
import com.rappi.buyer.repo.TransferOrderRepo;
import com.rappi.buyer.tools.TransferService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rice from Usaquen to Chapinero against the real database. Every test puts the
 * stock back afterwards, so the seed stays as it was.
 */
@SpringBootTest
class TransferIT {

    @Autowired TransferService transfers;
    @Autowired TransferOrderRepo transferOrders;
    @Autowired Verifier verifier;
    @Autowired InventoryRepo inventory;
    @Autowired AgentRunRepo runs;
    @Autowired ApprovalRepo approvals;
    @Autowired ReorderPlanner planner;

    static final String SKU = "SKU-RICE-5KG";
    static final String FROM = "NODE-BOG-02";
    static final String TO = "NODE-BOG-01";

    String madeId;

    @AfterEach
    void putTheStockBack() {
        if (madeId == null) return;
        TransferOrder t = transferOrders.findById(madeId).orElseThrow();
        Inventory from = inv(FROM);
        from.setOnHand(from.getOnHand() + t.getQty());
        inventory.save(from);
        Inventory to = inv(TO);
        to.setInTransit(to.getInTransit() - t.getQty());
        inventory.save(to);
        t.setStatus(TransferOrder.Status.CANCELLED);
        transferOrders.save(t);
        madeId = null;
    }

    Inventory inv(String node) {
        return inventory.findByNodeIdAndSku(node, SKU).orElseThrow();
    }

    String run() {
        AgentRun r = new AgentRun();
        r.setId(UUID.randomUUID().toString());
        r.setScenario("TEST");
        r.setInput("{}");
        r.setStatus(AgentRun.Status.RUNNING);
        r.setCreatedAt(Instant.now());
        runs.save(r);
        return r.getId();
    }

    String approve(String runId, int qty) {
        Approval a = new Approval();
        a.setId(UUID.randomUUID().toString());
        a.setRunId(runId);
        a.setReason("test");
        a.setRiskTier("T3");
        a.setProposedAction(("{\"type\":\"transfer\",\"sku\":\"%s\",\"fromNode\":\"%s\","
                + "\"toNode\":\"%s\",\"qty\":%d}").formatted(SKU, FROM, TO, qty));
        a.setStatus(Approval.Status.APPROVED);
        a.setCreatedAt(Instant.now());
        approvals.save(a);
        return a.getId();
    }

    @Test
    @DisplayName("a transfer with no approval is refused - POL-TRANSFER-01 says operations approves every one")
    void noApprovalNoTransfer() {
        TransferService.Result res = transfers.create(SKU, FROM, TO, 188, "t-" + UUID.randomUUID(), null, run());
        assertThat(res.executed()).isFalse();
        assertThat(res.message()).contains("POL-TRANSFER-01");
    }

    @Test
    @DisplayName("approved: stock moves, the receiver's plan stays consistent, and it verifies at all three levels")
    void approvedTransferMovesAndVerifies() {
        int fromBefore = inv(FROM).getOnHand();
        int toInTransitBefore = inv(TO).getInTransit();
        String run = run();

        TransferService.Result res = transfers.create(SKU, FROM, TO, 188, "t-" + UUID.randomUUID(),
                approve(run, 188), run);
        assertThat(res.executed()).as(res.message()).isTrue();
        madeId = res.transferId();

        assertThat(res.transferId()).startsWith("TO-");
        assertThat(inv(FROM).getOnHand()).isEqualTo(fromBefore - 188);
        assertThat(inv(TO).getInTransit()).isEqualTo(toInTransitBefore + 188);
        // the planner counts the transfer as incoming, so no false data conflict
        assertThat(planner.plan(SKU, TO, "SUP-ARROZMX").warnings())
                .noneMatch(w -> w.startsWith("DATA_CONFLICT"));

        VerificationReport v = verifier.verifyTransfer(res.transferId(), SKU, FROM, TO, 188);
        v.diffs().forEach(d -> System.out.printf("  %-3s %-24s intended %-16s actual %-16s %s%n",
                d.level(), d.field(), d.intended(), d.actual(), d.ok() ? "ok" : "MISMATCH"));
        assertThat(v.diffs()).filteredOn(d -> d.level().equals("L1")).allMatch(VerificationReport.Diff::ok);
        assertThat(v.diffs()).filteredOn(d -> d.level().equals("L2")).allMatch(VerificationReport.Diff::ok);
    }

    @Test
    @DisplayName("an approval does not let a store give away its own safety stock")
    void overSpareIsRefusedEvenWhenApproved() {
        String run = run();
        TransferService.Result res = transfers.create(SKU, FROM, TO, 1400, "t-" + UUID.randomUUID(),
                approve(run, 1400), run);
        assertThat(res.executed()).isFalse();
        assertThat(res.message()).startsWith("OVER_SPARE");
    }

    @Test
    @DisplayName("an approval for 188 does not cover 200")
    void approvalMustMatch() {
        String run = run();
        TransferService.Result res = transfers.create(SKU, FROM, TO, 200, "t-" + UUID.randomUUID(),
                approve(run, 188), run);
        assertThat(res.executed()).isFalse();
        assertThat(res.message()).contains("different transfer");
    }
}
