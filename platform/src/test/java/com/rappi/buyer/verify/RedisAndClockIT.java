package com.rappi.buyer.verify;

import com.rappi.buyer.api.ApprovalController;
import com.rappi.buyer.api.RunController;
import com.rappi.buyer.api.RunLock;
import com.rappi.buyer.domain.Approval;
import com.rappi.buyer.domain.Budget;
import com.rappi.buyer.domain.Inventory;
import com.rappi.buyer.domain.Supplier;
import com.rappi.buyer.repo.ApprovalRepo;
import com.rappi.buyer.repo.BudgetRepo;
import com.rappi.buyer.repo.InventoryRepo;
import com.rappi.buyer.repo.SupplierRepo;
import com.rappi.buyer.tools.MasterData;
import com.rappi.buyer.tools.PurchaseOrderService;
import com.rappi.buyer.tools.WriteToolController;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 7 against real redis and mysql. Needs docker compose up. */
@SpringBootTest
class RedisAndClockIT {

    @Autowired RunController runController;
    @Autowired WriteToolController writeTools;
    @Autowired ApprovalController approvalController;
    @Autowired RunLock lock;
    @Autowired MasterData masterData;
    @Autowired CacheManager caches;
    @Autowired PurchaseOrderService orders;
    @Autowired ApprovalRepo approvals;
    @Autowired InventoryRepo inventory;
    @Autowired BudgetRepo budgets;
    @Autowired SupplierRepo suppliers;

    @SuppressWarnings("unchecked")
    String start(String sku) throws Exception {
        ResponseEntity<?> res = runController.start(
                new RunController.StartRun("S1_REVIEW", sku, "NODE-BOG-01", null, null, null));
        return res.getStatusCode().is2xxSuccessful() ? (String) ((Map<String, Object>) res.getBody()).get("runId") : null;
    }

    void decide(String runId) throws Exception {
        writeTools.recordDecision(new WriteToolController.RecordDecision(
                "REJECT", 0, "test", null, null, null, null, null), runId);
    }

    // ---- run lock --------------------------------------------------------

    @Test
    @DisplayName("a second run on the same sku waits for the first to record its decision")
    void oneRunPerSku() throws Exception {
        String sku = "SKU-LOCK-" + UUID.randomUUID().toString().substring(0, 8);
        String first = start(sku);
        assertThat(first).isNotNull();

        ResponseEntity<?> second = runController.start(
                new RunController.StartRun("S1_REVIEW", sku, "NODE-BOG-01", null, null, null));
        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(second.getBody().toString()).contains(first);

        decide(first);
        assertThat(start(sku)).as("free again after the decision").isNotNull();
    }

    @Test
    @DisplayName("a run that is not the holder cannot release someone else's lock")
    void staleReleaseDoesNothing() throws Exception {
        String sku = "SKU-LOCK-" + UUID.randomUUID().toString().substring(0, 8);
        String holder = start(sku);

        assertThat(lock.release(sku, "NODE-BOG-01", "some-old-run")).isFalse();
        assertThat(start(sku)).as("still held").isNull();
        assertThat(lock.release(sku, "NODE-BOG-01", holder)).isTrue();
    }

    @Test
    @DisplayName("a run parked on a buyer does not keep the sku locked")
    void parkedRunReleases() throws Exception {
        String sku = "SKU-LOCK-" + UUID.randomUUID().toString().substring(0, 8);
        String run = start(sku);
        writeTools.requestApproval(new WriteToolController.RequestApproval("test", "T3", Map.of()), run);
        assertThat(start(sku)).isNotNull();
    }

    // ---- cache -----------------------------------------------------------

    @Test
    @DisplayName("supplier lookups come back from redis intact, and a receipt evicts them after it commits")
    void supplierCacheIsEvictedByAReceipt() {
        String sku = "SKU-CHIPS-150G";
        masterData.suppliersChanged();
        List<Map<String, Object>> fresh = masterData.suppliers(sku);
        assertThat(caches.getCache("suppliers").get(sku)).as("cached").isNotNull();
        assertThat(masterData.suppliers(sku)).as("read back from redis").isEqualTo(fresh);

        // a receipt changes reliability, so the cached list must go
        Inventory i = inventory.findByNodeIdAndSku("NODE-BOG-01", sku).orElseThrow();
        Budget b = budgets.findByNodeIdAndCategoryAndPeriod("NODE-BOG-01", "snacks", "2026-03").orElseThrow();
        Supplier s = suppliers.findById("SUP-SNACKCO").orElseThrow();
        int onHand = i.getOnHand(), inTransit = i.getInTransit();
        BigDecimal committed = b.getCommitted(), spent = b.getSpent(), score = s.getReliabilityScore();

        String po = orders.create(sku, "NODE-BOG-01", "SUP-SNACKCO", 240, new BigDecimal("0.62"),
                LocalDate.of(2026, 3, 16), "cache-" + UUID.randomUUID(), 240, null, "test").poId();
        orders.receive(po, 240, LocalDate.of(2026, 3, 15));
        assertThat(caches.getCache("suppliers").get(sku)).as("evicted").isNull();

        i = inventory.findByNodeIdAndSku("NODE-BOG-01", sku).orElseThrow();
        i.setOnHand(onHand);
        i.setInTransit(inTransit);
        inventory.save(i);
        b = budgets.findByNodeIdAndCategoryAndPeriod("NODE-BOG-01", "snacks", "2026-03").orElseThrow();
        b.setCommitted(committed);
        b.setSpent(spent);
        budgets.save(b);
        s = suppliers.findById("SUP-SNACKCO").orElseThrow();
        s.setReliabilityScore(score);
        suppliers.save(s);
        masterData.suppliersChanged();
    }

    // ---- real clock for audit times --------------------------------------

    @Test
    @DisplayName("approvals are stamped with the real time, not the frozen simulation date")
    void approvalsUseTheRealClock() throws Exception {
        String run = start("SKU-CLOCK-" + UUID.randomUUID().toString().substring(0, 8));
        Map<String, Object> res = writeTools.requestApproval(
                new WriteToolController.RequestApproval("test", "T3", Map.of()), run).data();
        Instant created = approvals.findById((String) res.get("approvalId")).orElseThrow().getCreatedAt();

        assertThat(created).isAfter(Instant.parse("2026-03-10T00:00:01Z"));
        assertThat(Duration.between(created, Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("an approval nobody touched for 25 hours expires")
    void staleApprovalExpires() throws Exception {
        String run = start("SKU-CLOCK-" + UUID.randomUUID().toString().substring(0, 8));
        Map<String, Object> res = writeTools.requestApproval(
                new WriteToolController.RequestApproval("test", "T3", Map.of()), run).data();
        Approval a = approvals.findById((String) res.get("approvalId")).orElseThrow();
        a.setCreatedAt(Instant.now().minus(Duration.ofHours(25)));
        approvals.save(a);

        approvalController.expireStale();

        assertThat(approvals.findById(a.getId()).orElseThrow().getStatus()).isEqualTo(Approval.Status.EXPIRED);
    }
}
