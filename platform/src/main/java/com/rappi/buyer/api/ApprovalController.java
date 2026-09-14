package com.rappi.buyer.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rappi.buyer.domain.AgentRun;
import com.rappi.buyer.domain.Approval;
import com.rappi.buyer.repo.AgentRunRepo;
import com.rappi.buyer.repo.ApprovalRepo;
import com.rappi.buyer.tools.ToolResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The human in the loop.
 *
 * Approving does not just mark a row - it resumes the agent, which then runs
 * the same execute and verify path an auto approved action would have taken.
 * One code path, so a human approved purchase gets checked exactly as
 * carefully as one the agent did on its own.
 */
@RestController
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);
    private static final Duration EXPIRY = Duration.ofHours(24);

    private final ApprovalRepo approvals;
    private final AgentRunRepo runs;
    private final ObjectMapper json;
    private final RestClient agent;
    private final Clock clock;

    public ApprovalController(ApprovalRepo approvals, AgentRunRepo runs, ObjectMapper json,
                              @Value("${app.agent-base-url}") String agentUrl,
                              Clock clock) {
        this.approvals = approvals;
        this.runs = runs;
        this.json = json;
        // Plain HTTP/1.1 on purpose. The default jdk client negotiates an upgrade
        // that uvicorn rejects with "unsupported upgrade request", and the body
        // gets dropped on the way - the agent then sees an empty request.
        this.agent = RestClient.builder()
                .baseUrl(agentUrl)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        this.clock = clock;
    }

    public record Decide(@NotBlank String decision, String note, String decidedBy) {}

    @GetMapping("/approvals")
    public List<Map<String, Object>> pending() {
        return approvals.findByStatusOrderByCreatedAtDesc(Approval.Status.PENDING)
                .stream().map(this::view).toList();
    }

    @GetMapping("/approvals/all")
    public List<Map<String, Object>> all() {
        return approvals.findTop50ByOrderByCreatedAtDesc().stream().map(this::view).toList();
    }

    @PostMapping("/approvals/{id}/decide")
    public ResponseEntity<?> decide(@PathVariable String id, @Valid @RequestBody Decide req) {
        Approval a = approvals.findById(id).orElse(null);
        if (a == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ToolResponse.error("NO_APPROVAL", "unknown approval " + id));
        }
        // guarded transition, so a double click cannot execute the purchase twice
        if (a.getStatus() != Approval.Status.PENDING) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ToolResponse.error("ALREADY_DECIDED",
                            "this was already " + a.getStatus() + " by " + a.getDecidedBy()));
        }

        boolean approved = "approve".equalsIgnoreCase(req.decision());
        if (!approved && (req.note() == null || req.note().isBlank())) {
            return ResponseEntity.badRequest().body(ToolResponse.error("NOTE_REQUIRED",
                    "say why you are rejecting it - that note is the only feedback the agent gets"));
        }

        a.setStatus(approved ? Approval.Status.APPROVED : Approval.Status.REJECTED);
        a.setDecidedBy(req.decidedBy() == null ? "buyer" : req.decidedBy());
        a.setDecidedAt(Instant.now(clock));
        a.setDecisionNote(req.note());
        approvals.save(a);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("approvalId", id);
        out.put("status", a.getStatus());

        if (!approved) {
            runs.findById(a.getRunId()).ifPresent(r -> {
                r.setStatus(AgentRun.Status.COMPLETED);
                r.setExplanation((r.getExplanation() == null ? "" : r.getExplanation())
                        + "\n\nRejected by " + a.getDecidedBy() + ": " + req.note());
                runs.save(r);
            });
            out.put("resumed", false);
            return ResponseEntity.ok(out);
        }

        // hand it back to the agent to execute and verify
        try {
            Map<?, ?> res = agent.post()
                    .uri("/resume/{runId}", a.getRunId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("approval_id", id,
                            "approved_action", json.readValue(a.getProposedAction(), Map.class)))
                    .retrieve()
                    .body(Map.class);
            out.put("resumed", true);
            out.put("result", res);
        } catch (Exception e) {
            log.warn("could not resume run {}", a.getRunId(), e);
            out.put("resumed", false);
            out.put("error", "approved, but the agent could not be reached: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Nobody actioned it in a day, so stop pretending it is waiting on someone.
     * Leaving runs PENDING forever is how a queue quietly becomes a graveyard.
     */
    @Scheduled(fixedDelay = 600_000)
    @Transactional
    public void expireStale() {
        Instant cutoff = Instant.now(clock).minus(EXPIRY);
        approvals.findByStatusOrderByCreatedAtDesc(Approval.Status.PENDING).stream()
                .filter(a -> a.getCreatedAt().isBefore(cutoff))
                .forEach(a -> {
                    a.setStatus(Approval.Status.EXPIRED);
                    approvals.save(a);
                    log.info("approval {} expired after 24h", a.getId());
                });
    }

    private Map<String, Object> view(Approval a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("runId", a.getRunId());
        m.put("riskTier", a.getRiskTier());
        m.put("status", a.getStatus());
        m.put("reason", a.getReason());
        m.put("createdAt", a.getCreatedAt());
        m.put("decidedBy", a.getDecidedBy());
        m.put("decisionNote", a.getDecisionNote());
        try {
            m.put("proposedAction", json.readValue(a.getProposedAction(), Map.class));
        } catch (Exception e) {
            m.put("proposedAction", a.getProposedAction());
        }
        return m;
    }

    /** Called by the request-approval tool. */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
