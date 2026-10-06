package com.rappi.buyer.tools;

import com.rappi.buyer.api.ApprovalController;
import com.rappi.buyer.constraints.Proposal;
import com.rappi.buyer.domain.AgentRun;
import com.rappi.buyer.domain.Approval;
import com.rappi.buyer.repo.AgentRunRepo;
import com.rappi.buyer.repo.ApprovalRepo;
import com.rappi.buyer.tools.PurchaseOrderService.WriteResult;
import com.rappi.buyer.verify.VerificationReport;
import com.rappi.buyer.verify.Verifier;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
    private final TransferService transfers;
    private final Verifier verifier;
    private final ApprovalRepo approvals;
    private final AgentRunRepo runs;
    private final ObjectMapper json;
    private final Clock clock;

    public WriteToolController(PurchaseOrderService orders, TransferService transfers, Verifier verifier,
                               ApprovalRepo approvals, AgentRunRepo runs,
                               ObjectMapper json, Clock clock) {
        this.orders = orders;
        this.transfers = transfers;
        this.verifier = verifier;
        this.approvals = approvals;
        this.runs = runs;
        this.json = json;
        this.clock = clock;
    }

    public record RequestApproval(
            @NotBlank String reason,
            @NotBlank String riskTier,
            Map<String, Object> proposedAction) {}

    public record RecordDecision(
            @NotBlank String decision,
            Integer finalQty,
            String explanation,
            Object validationReport,
            Integer llmCalls,
            Integer tokensIn,
            Integer tokensOut,
            Long durationMs) {}

    /**
     * Park a decision the agent is not allowed to make. The action is stored
     * whole so approving it later executes exactly what was queued, rather than
     * asking the model to think again and possibly land somewhere else.
     */
    @PostMapping("/request-approval")
    public ToolResponse<Map<String, Object>> requestApproval(
            @Valid @RequestBody RequestApproval req,
            @RequestHeader("X-Run-Id") String runId) throws Exception {

        Approval a = new Approval();
        a.setId(ApprovalController.newId());
        a.setRunId(runId);
        a.setReason(req.reason());
        a.setRiskTier(req.riskTier());
        a.setProposedAction(json.writeValueAsString(
                req.proposedAction() == null ? Map.of() : req.proposedAction()));
        a.setStatus(Approval.Status.PENDING);
        a.setCreatedAt(Instant.now(clock));
        approvals.save(a);

        runs.findById(runId).ifPresent(r -> {
            r.setStatus(AgentRun.Status.NEEDS_APPROVAL);
            runs.save(r);
        });

        return ToolResponse.ok(Map.of(
                "approvalId", a.getId(),
                "status", a.getStatus().name(),
                "message", "queued for a buyer, nothing has been ordered"));
    }

    /** Terminal step. A run that never records a decision is a run nobody can audit. */
    @PostMapping("/record-decision")
    public ToolResponse<Map<String, Object>> recordDecision(
            @Valid @RequestBody RecordDecision req,
            @RequestHeader("X-Run-Id") String runId) throws Exception {

        AgentRun run = runs.findById(runId).orElseThrow(
                () -> new ToolController.NotFound("NO_RUN", "unknown run " + runId));
        run.setDecision(AgentRun.Decision.valueOf(req.decision()));
        run.setFinalQty(req.finalQty());
        run.setExplanation(req.explanation());
        if (req.validationReport() != null) {
            run.setValidationReport(json.writeValueAsString(req.validationReport()));
        }
        if (req.llmCalls() != null) run.setLlmCalls(req.llmCalls());
        if (req.tokensIn() != null) run.setTokensIn(req.tokensIn());
        if (req.tokensOut() != null) run.setTokensOut(req.tokensOut());
        if (req.durationMs() != null) run.setDurationMs(req.durationMs());
        if (run.getStatus() == AgentRun.Status.RUNNING) {
            run.setStatus(AgentRun.Status.COMPLETED);
        }
        runs.save(run);
        return ToolResponse.ok(Map.of("ok", true, "runId", runId, "decision", req.decision()));
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
            String reason,
            String approvalId) {}

    public record AmendPo(
            @NotBlank String poId,
            @Min(0) int newQty,
            int expectedVersion,
            String reason) {}

    public record CancelPo(@NotBlank String poId, int expectedVersion, String reason) {}

    public record CreateTransfer(
            @NotBlank String sku,
            @NotBlank String fromNode,
            @NotBlank String toNode,
            @Min(value = 1, message = "quantity must be at least 1") int qty,
            @NotBlank String idempotencyKey,
            String approvalId) {}

    /**
     * Move stock from one store to another. Always needs an approved approval of
     * exactly this transfer, and is verified in the same response like a PO.
     */
    @PostMapping("/create-transfer")
    public ToolResponse<Map<String, Object>> createTransfer(@Valid @RequestBody CreateTransfer req,
                                                            @RequestHeader("X-Run-Id") String runId) {
        TransferService.Result res = transfers.create(req.sku(), req.fromNode(), req.toNode(),
                req.qty(), req.idempotencyKey(), req.approvalId(), runId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executed", res.executed());
        out.put("idempotent", res.idempotent());
        out.put("transferId", res.transferId());
        out.put("message", res.message());
        if (res.executed() && !res.idempotent()) {
            VerificationReport v = verifier.verifyTransfer(res.transferId(), req.sku(),
                    req.fromNode(), req.toNode(), req.qty());
            out.put("verification", v);
            out.put("verified", v.verified());
        }
        return ToolResponse.ok(out);
    }

    public record VerifyPo(
            @NotBlank String poId,
            @NotBlank String sku,
            @NotBlank String nodeId,
            @NotBlank String supplierId,
            @Min(1) int qty,
            @NotNull BigDecimal unitPrice,
            LocalDate expectedDelivery) {}

    /**
     * Check an order again after a repair changed it. A repair that is not
     * re-verified is the agent reading its own optimism a second time.
     */
    @PostMapping("/verify-po")
    public ToolResponse<VerificationReport> verifyPo(@Valid @RequestBody VerifyPo req,
                                                     @RequestHeader("X-Run-Id") String runId) {
        return ToolResponse.ok(verifier.verify(req.poId(), new Proposal(req.sku(), req.nodeId(),
                req.supplierId(), req.qty(), req.unitPrice(), req.expectedDelivery(), null)));
    }

    public record SupplierEventReq(
            @NotBlank String poId,
            @NotBlank String sender,
            @NotBlank @Pattern(regexp = "CONFIRM|PARTIAL|DELAY|PRICE_CHANGE|OTHER",
                    message = "must be CONFIRM, PARTIAL, DELAY, PRICE_CHANGE or OTHER") String kind,
            @Min(0) Integer confirmedQty,
            LocalDate confirmedDelivery,
            @DecimalMin(value = "0.01", message = "unit price must be positive") BigDecimal unitPrice,
            String rawText) {}

    /**
     * A supplier message the agent has read. The platform checks it before anything
     * changes - the model's reading of an email is a claim, not a fact.
     */
    @PostMapping("/supplier-event")
    public ToolResponse<Map<String, Object>> supplierEvent(@Valid @RequestBody SupplierEventReq req,
                                                           @RequestHeader("X-Run-Id") String runId) {
        Map<String, Object> out = orders.applySupplierEvent(req.poId(), req.sender(), req.kind(),
                req.confirmedQty(), req.confirmedDelivery(), req.unitPrice(), req.rawText(), runId);
        if (out.containsKey("error")) {
            return ToolResponse.error((String) out.get("error"), (String) out.get("detail"));
        }
        return ToolResponse.ok(out);
    }

    @PostMapping("/create-po")
    public ToolResponse<Map<String, Object>> create(@Valid @RequestBody CreatePo req,
                                                    @RequestHeader("X-Run-Id") String runId) {
        WriteResult res = orders.create(req.sku(), req.nodeId(), req.supplierId(), req.qty(),
                req.unitPrice(), req.expectedDelivery(), req.idempotencyKey(),
                req.recommendedQty(), runId, "agent", req.approvalId());

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
