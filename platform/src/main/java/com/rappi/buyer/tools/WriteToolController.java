package com.rappi.buyer.tools;

import com.rappi.buyer.constraints.Proposal;
import com.rappi.buyer.tools.PurchaseOrderService.WriteResult;
import com.rappi.buyer.verify.VerificationReport;
import com.rappi.buyer.verify.Verifier;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The tools that change something. Every one requires X-Run-Id - the interceptor
 * refuses the request otherwise, because an action nobody can audit afterwards
 * is not one worth taking.
 */
@RestController
@RequestMapping("/tools")
public class WriteToolController {

    private final PurchaseOrderService orders;
    private final Verifier verifier;

    public WriteToolController(PurchaseOrderService orders, Verifier verifier) {
        this.orders = orders;
        this.verifier = verifier;
    }

    public record CreatePo(
            @NotBlank String sku,
            @NotBlank String nodeId,
            @NotBlank String supplierId,
            @Min(value = 1, message = "quantity must be at least 1") int qty,
            @NotNull @DecimalMin(value = "0.01", message = "unit price must be positive")
            BigDecimal unitPrice,
            @NotNull LocalDate expectedDelivery,
            @NotBlank String idempotencyKey,
            Integer recommendedQty,
            String reason) {}

    public record AmendPo(
            @NotBlank String poId,
            @Min(0) int newQty,
            int expectedVersion,
            String reason) {}

    public record CancelPo(@NotBlank String poId, int expectedVersion, String reason) {}

    @PostMapping("/create-po")
    public ToolResponse<Map<String, Object>> create(@Valid @RequestBody CreatePo req,
                                                    @RequestHeader("X-Run-Id") String runId) {
        WriteResult res = orders.create(req.sku(), req.nodeId(), req.supplierId(), req.qty(),
                req.unitPrice(), req.expectedDelivery(), req.idempotencyKey(),
                req.recommendedQty(), runId, "agent");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executed", res.executed());
        out.put("idempotent", res.idempotent());
        out.put("poId", res.poId());
        out.put("message", res.message());

        if (!res.executed()) {
            // refused, so nothing was written. the agent should raise an approval
            // rather than try again with the same arguments.
            out.put("requiresApproval", true);
            out.put("validation", res.validation());
            return ToolResponse.ok(out);
        }

        // never report success from the write's own return value - go and look
        Proposal intended = new Proposal(req.sku(), req.nodeId(), req.supplierId(), req.qty(),
                req.unitPrice(), req.expectedDelivery(), req.recommendedQty());
        VerificationReport verification = verifier.verify(res.poId(), intended);

        out.put("validation", res.validation());
        out.put("verification", verification);
        out.put("verified", verification.verified());
        return ToolResponse.ok(out);
    }

    @PostMapping("/amend-po")
    public ToolResponse<Map<String, Object>> amend(@Valid @RequestBody AmendPo req,
                                                   @RequestHeader("X-Run-Id") String runId) {
        WriteResult res = orders.amend(req.poId(), req.newQty(), req.expectedVersion(), req.reason());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executed", res.executed());
        out.put("poId", res.poId());
        out.put("message", res.message());
        if (res.executed()) {
            out.put("po", orders.reread(req.poId()).getVersion());
        }
        return ToolResponse.ok(out);
    }

    @PostMapping("/cancel-po")
    public ToolResponse<Map<String, Object>> cancel(@Valid @RequestBody CancelPo req,
                                                    @RequestHeader("X-Run-Id") String runId) {
        WriteResult res = orders.cancel(req.poId(), req.expectedVersion(), req.reason());
        return ToolResponse.ok(Map.of(
                "executed", res.executed(),
                "poId", String.valueOf(res.poId()),
                "message", String.valueOf(res.message())));
    }
}
