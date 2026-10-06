package com.rappi.buyer.api;

import com.rappi.buyer.domain.TransferOrder;
import com.rappi.buyer.repo.TransferOrderRepo;
import com.rappi.buyer.tools.PurchaseOrderService;
import com.rappi.buyer.tools.ToolResponse;
import com.rappi.buyer.tools.TransferService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final Clock clock;

    public PoController(PurchaseOrderService orders, TransferService transfers,
                        TransferOrderRepo transferOrders, Clock clock) {
        this.orders = orders;
        this.transfers = transfers;
        this.transferOrders = transferOrders;
        this.clock = clock;
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
