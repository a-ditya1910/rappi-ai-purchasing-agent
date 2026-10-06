package com.rappi.buyer.tools;

import com.rappi.buyer.domain.Inventory;
import com.rappi.buyer.domain.TransferOrder;
import com.rappi.buyer.planner.TransferPlanner;
import com.rappi.buyer.planner.TransferPlanner.Option;
import com.rappi.buyer.planner.TransferPlanner.TransferPlan;
import com.rappi.buyer.repo.InventoryRepo;
import com.rappi.buyer.repo.TransferOrderRepo;

import jakarta.persistence.EntityManager;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Moving stock between dark stores.
 *
 * Every transfer needs a buyer-approved approval of exactly this transfer, with no
 * exceptions - POL-TRANSFER-01 says operations approves each one, because the
 * sending store's service level drops. And the approval grants authority, not
 * legality: the spare stock is worked out again here, since the shelf can have
 * changed since the agent proposed it.
 */
@Service
public class TransferService {

    public record Result(boolean executed, boolean idempotent, String transferId, String message) {}

    private final TransferOrderRepo transfers;
    private final InventoryRepo inventory;
    private final TransferPlanner planner;
    private final PurchaseOrderService orders;
    private final EntityManager em;
    private final Clock clock;

    public TransferService(TransferOrderRepo transfers, InventoryRepo inventory,
                           TransferPlanner planner, PurchaseOrderService orders,
                           EntityManager em, Clock clock) {
        this.transfers = transfers;
        this.inventory = inventory;
        this.planner = planner;
        this.orders = orders;
        this.em = em;
        this.clock = clock;
    }

    @Transactional
    public Result create(String sku, String fromNode, String toNode, int qty,
                         String idempotencyKey, String approvalId, String runId) {

        Optional<TransferOrder> existing = transfers.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return new Result(true, true, existing.get().getId(), "already created under this idempotency key");
        }

        if (approvalId == null) {
            return refused("transfers always need operations approval (POL-TRANSFER-01), raise one first");
        }
        String why = orders.approvalProblem(approvalId, runId, Map.of("type", "transfer",
                "sku", sku, "fromNode", fromNode, "toNode", toNode, "qty", qty));
        if (why != null) {
            return refused(why);
        }

        TransferPlan plan = planner.plan(sku, toNode);
        Option from = plan.options().stream().filter(o -> o.fromNode().equals(fromNode))
                .findFirst().orElse(null);
        if (from == null) {
            return refused(fromNode + " holds no " + sku);
        }
        if (qty % plan.casePack() != 0) {
            return refused("%d is not whole cases of %d".formatted(qty, plan.casePack()));
        }
        if (qty > from.spare()) {
            return refused("OVER_SPARE: %s can spare %d, %d requested - it has to keep %d for itself"
                    .formatted(fromNode, from.spare(), qty, from.keep()));
        }
        if (qty > plan.maxStorable()) {
            return refused("STORAGE: %s has room for %d, %d requested".formatted(toNode, plan.maxStorable(), qty));
        }

        LocalDate arrival = LocalDate.now(clock).plusDays(plan.leadDays());
        TransferOrder t = new TransferOrder();
        t.setId(orders.nextId("TO"));
        t.setFromNode(fromNode);
        t.setToNode(toNode);
        t.setSku(sku);
        t.setQty(qty);
        t.setStatus(TransferOrder.Status.IN_TRANSIT);
        t.setExpectedArrival(arrival);
        t.setIdempotencyKey(idempotencyKey);
        t.setApprovalId(approvalId);
        t.setAgentRunId(runId);
        t.setCreatedAt(Instant.now(clock));
        try {
            transfers.saveAndFlush(t);
        } catch (DataIntegrityViolationException e) {
            return transfers.findByIdempotencyKey(idempotencyKey)
                    .map(w -> new Result(true, true, w.getId(), "created concurrently"))
                    .orElseThrow(() -> e);
        }

        // the stock leaves the sender's shelf now and is on its way to the receiver,
        // in the same transaction as the transfer row
        Inventory sender = inventory.findByNodeIdAndSku(fromNode, sku).orElseThrow();
        sender.setOnHand(sender.getOnHand() - qty);
        inventory.save(sender);
        Inventory receiver = inventory.findByNodeIdAndSku(toNode, sku).orElseThrow();
        receiver.setInTransit(receiver.getInTransit() + qty);
        inventory.save(receiver);

        return new Result(true, false, t.getId(), "in transit, arrives " + arrival);
    }

    /** Straight from mysql, for the same reason as PurchaseOrderService.reread. */
    @Transactional(readOnly = true)
    public TransferOrder reread(String id) {
        em.clear();
        return transfers.findById(id).orElseThrow();
    }

    private static Result refused(String why) {
        return new Result(false, false, null, why);
    }
}
