package com.rappi.buyer.api;

import com.rappi.buyer.domain.Inventory;
import com.rappi.buyer.domain.PurchaseOrder;
import com.rappi.buyer.domain.TransferOrder;
import com.rappi.buyer.repo.InventoryRepo;
import com.rappi.buyer.repo.PurchaseOrderRepo;
import com.rappi.buyer.repo.TransferOrderRepo;
import com.rappi.buyer.tools.PurchaseOrderService;
import com.rappi.buyer.tools.PurchaseOrderService.WriteResult;
import com.rappi.buyer.tools.ToolResponse;
import com.rappi.buyer.tools.TransferService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The warehouse side: goods arriving at a store. Not an agent tool - nobody
 * should be able to "receive" stock by asking a model to - so it lives outside
 * /tools and the console calls it like a store worker scanning a delivery would.
 */
@RestController
public class PoController {

    private final PurchaseOrderService orders;
    private final TransferService transfers;
    private final TransferOrderRepo transferOrders;
    private final InventoryRepo inventory;
    private final PurchaseOrderRepo purchaseOrders;
    private final Clock clock;

    public PoController(PurchaseOrderService orders, TransferService transfers,
                        TransferOrderRepo transferOrders, InventoryRepo inventory,
                        PurchaseOrderRepo purchaseOrders, Clock clock) {
        this.orders = orders;
        this.transfers = transfers;
        this.transferOrders = transferOrders;
        this.inventory = inventory;
        this.purchaseOrders = purchaseOrders;
        this.clock = clock;
    }

    public record Sell(@NotBlank String sku, @NotBlank String nodeId, @Min(1) int qty) {}

    /**
     * Stands in for the store's checkout feed: sold stock leaves the shelf. The
     * simulation has no sales of its own, so without this the position never
     * drops and every run after an order says there is nothing to buy.
     */
    @Transactional
    @PostMapping("/inventory/sell")
    public ResponseEntity<?> sell(@Valid @RequestBody Sell req) {
        Inventory i = inventory.findByNodeIdAndSku(req.nodeId(), req.sku()).orElse(null);
        if (i == null) {
            return conflict("NO_INVENTORY", "no stock of " + req.sku() + " at " + req.nodeId());
        }
        // reserved units are already promised to customers
        if (req.qty() > i.available()) {
            return conflict("NOT_ENOUGH_STOCK", "only " + i.available() + " available to sell");
        }
        i.setOnHand(i.getOnHand() - req.qty());
        inventory.save(i);
        return ResponseEntity.ok(Map.of("sku", req.sku(), "nodeId", req.nodeId(),
                "sold", req.qty(), "onHand", i.getOnHand()));
    }

    /**
     * A buyer cancelling an order. Same service the agent's repair uses, so the
     * budget and in_transit move back with it and the numbers still add up.
     */
    @PostMapping("/pos/{id}/cancel")
    public ResponseEntity<?> cancelPo(@PathVariable String id) {
        PurchaseOrder po = purchaseOrders.findById(id).orElse(null);
        if (po == null) {
            return conflict("NO_PO", "unknown purchase order " + id);
        }
        WriteResult res = orders.cancel(id, po.getVersion(), "cancelled by a buyer");
        if (!res.executed()) {
            return conflict("CANNOT_CANCEL", res.message());
        }
        return ResponseEntity.ok(Map.of("poId", id, "message", id + " cancelled"));
    }

    private static ResponseEntity<?> conflict(String code, String detail) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ToolResponse.error(code, detail));
    }

    public record Receive(@Min(0) int qtyReceived, LocalDate receivedDate) {}

    @PostMapping("/pos/{id}/receive")
    public ResponseEntity<?> receivePo(@PathVariable String id, @Valid @RequestBody Receive req) {
        Map<String, Object> out = orders.receive(id, req.qtyReceived(),
                req.receivedDate() != null ? req.receivedDate() : LocalDate.now(clock));
        if (out.containsKey("error")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ToolResponse.error((String) out.get("error"), (String) out.get("detail")));
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/transfers")
    public List<TransferOrder> incoming(@RequestParam String nodeId, @RequestParam String sku) {
        return transferOrders.findIncoming(nodeId, sku);
    }

    @PostMapping("/transfers/{id}/receive")
    public ResponseEntity<?> receiveTransfer(@PathVariable String id) {
        TransferService.Result res = transfers.receive(id);
        if (!res.executed()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ToolResponse.error("CANNOT_RECEIVE", res.message()));
        }
        return ResponseEntity.ok(Map.of("transferId", id, "message", res.message()));
    }
}
