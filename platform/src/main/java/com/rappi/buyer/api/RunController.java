package com.rappi.buyer.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rappi.buyer.domain.AgentRun;
import com.rappi.buyer.domain.AgentStep;
import com.rappi.buyer.repo.AgentRunRepo;
import com.rappi.buyer.repo.AgentStepRepo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.beans.factory.annotation.Qualifier;
import com.rappi.buyer.tools.ToolResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Run lifecycle. Minimal for now - opening a run so tool calls have something to
 * trace into, and letting the agent post its own reasoning steps. The lock,
 * approvals and resume land in block 7.
 */
@RestController
public class RunController {

    private final AgentRunRepo runs;
    private final AgentStepRepo steps;
    private final ObjectMapper json;
    private final RunLock lock;
    private final Clock clock;

    public RunController(AgentRunRepo runs, AgentStepRepo steps, ObjectMapper json, RunLock lock,
                         @Qualifier("wallClock") Clock clock) {
        this.runs = runs;
        this.lock = lock;
        this.steps = steps;
        this.json = json;
        this.clock = clock;
    }

    public record StartRun(
            @NotBlank String scenario,
            String sku,
            String nodeId,
            String supplierId,
            Integer recommendedQty,
            String poId) {}

    public record PostStep(@NotBlank String type, @NotBlank String name, Object payload,
                           Integer latencyMs, Integer tokens) {}

    @PostMapping("/runs")
    public ResponseEntity<?> start(@Valid @RequestBody StartRun req) throws Exception {
        String runId = UUID.randomUUID().toString();

        if (req.sku() != null && req.nodeId() != null) {
            String holder;
            try {
                holder = lock.acquire(req.sku(), req.nodeId(), runId);
            } catch (RuntimeException e) {
                // fail closed: a paused agent is better than two runs buying the same thing
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ToolResponse.error(
                        "LOCK_UNAVAILABLE", "cannot take the run lock, redis is unreachable: " + e.getMessage()));
            }
            if (holder != null) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "ok", false, "error", "RUN_IN_PROGRESS", "runId", holder,
                        "detail", "another run is already working on %s at %s".formatted(req.sku(), req.nodeId())));
            }
        }

        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setScenario(req.scenario());
        run.setSku(req.sku());
        run.setNodeId(req.nodeId());
        run.setInput(json.writeValueAsString(req));
        run.setStatus(AgentRun.Status.RUNNING);
        run.setCreatedAt(Instant.now(clock));
        runs.save(run);

        return ResponseEntity.ok(Map.of("runId", run.getId(), "status", run.getStatus()));
    }

    /** The agent's own events. Tool calls trace themselves via the interceptor. */
    @PostMapping("/runs/{runId}/steps")
    public Map<String, Object> addStep(@PathVariable String runId,
                                       @Valid @RequestBody PostStep req) throws Exception {
        AgentStep step = new AgentStep();
        step.setRunId(runId);
        step.setSeq(steps.nextSeq(runId));
        step.setType(AgentStep.Type.valueOf(req.type()));
        step.setName(req.name());
        step.setPayload(json.writeValueAsString(req.payload()));
        step.setLatencyMs(req.latencyMs());
        step.setTokens(req.tokens());
        step.setCreatedAt(Instant.now(clock));
        steps.save(step);
        return Map.of("ok", true, "seq", step.getSeq());
    }

    public record Result(Object result, String error) {}

    /**
     * The end of a background run. A result is stored for the console to read; an
     * error marks the run FAILED and frees its lock, so a crashed run neither shows
     * as RUNNING forever nor blocks the sku until the lock expires.
     */
    @PutMapping("/runs/{runId}/result")
    public ResponseEntity<?> saveResult(@PathVariable String runId, @RequestBody Result req) throws Exception {
        AgentRun run = runs.findById(runId).orElse(null);
        if (run == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ToolResponse.error("NO_RUN", runId));
        }
        if (req.result() != null) {
            run.setResult(json.writeValueAsString(req.result()));
        }
        if (req.error() != null) {
            run.setError(req.error());
            run.setStatus(AgentRun.Status.FAILED);
            lock.release(run.getSku(), run.getNodeId(), runId);
        }
        runs.save(run);
        return ResponseEntity.ok(Map.of("ok", true, "status", run.getStatus()));
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String runId) throws Exception {
        AgentRun run = runs.findById(runId).orElse(null);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.getId());
        out.put("scenario", run.getScenario());
        out.put("status", run.getStatus());
        out.put("decision", run.getDecision());
        out.put("finalQty", run.getFinalQty());
        out.put("explanation", run.getExplanation());
        out.put("createdAt", run.getCreatedAt());
        out.put("llmCalls", run.getLlmCalls());
        out.put("tokensIn", run.getTokensIn());
        out.put("tokensOut", run.getTokensOut());
        out.put("durationMs", run.getDurationMs());
        out.put("result", run.getResult() == null ? null : json.readTree(run.getResult()));
        out.put("error", run.getError());
        out.put("steps", steps.findByRunIdOrderBySeq(runId).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", s.getSeq());
            m.put("type", s.getType());
            m.put("name", s.getName());
            m.put("latencyMs", s.getLatencyMs());
            m.put("tokens", s.getTokens());
            m.put("payload", s.getPayload());
            return m;
        }).toList());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> recent() {
        return runs.findTop50ByOrderByCreatedAtDesc().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", r.getId());
            m.put("scenario", r.getScenario());
            m.put("sku", r.getSku());
            m.put("status", r.getStatus());
            m.put("decision", r.getDecision());
            m.put("createdAt", r.getCreatedAt());
            return m;
        }).toList();
    }
}
