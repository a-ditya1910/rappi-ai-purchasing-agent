package com.rappi.buyer.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rappi.buyer.domain.AgentRun;
import com.rappi.buyer.domain.AgentStep;
import com.rappi.buyer.repo.AgentRunRepo;
import com.rappi.buyer.repo.AgentStepRepo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final Clock clock;

    public RunController(AgentRunRepo runs, AgentStepRepo steps, ObjectMapper json, Clock clock) {
        this.runs = runs;
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

    public record PostStep(@NotBlank String type, @NotBlank String name, Object payload) {}

    @PostMapping("/runs")
    public Map<String, Object> start(@Valid @RequestBody StartRun req) throws Exception {
        AgentRun run = new AgentRun();
        run.setId(UUID.randomUUID().toString());
        run.setScenario(req.scenario());
        run.setSku(req.sku());
        run.setNodeId(req.nodeId());
        run.setInput(json.writeValueAsString(req));
        run.setStatus(AgentRun.Status.RUNNING);
        run.setCreatedAt(Instant.now(clock));
        runs.save(run);

        return Map.of("runId", run.getId(), "status", run.getStatus());
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
        step.setCreatedAt(Instant.now(clock));
        steps.save(step);
        return Map.of("ok", true, "seq", step.getSeq());
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> get(@PathVariable String runId) {
        AgentRun run = runs.findById(runId).orElseThrow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.getId());
        out.put("scenario", run.getScenario());
        out.put("status", run.getStatus());
        out.put("decision", run.getDecision());
        out.put("finalQty", run.getFinalQty());
        out.put("explanation", run.getExplanation());
        out.put("createdAt", run.getCreatedAt());
        out.put("steps", steps.findByRunIdOrderBySeq(runId).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", s.getSeq());
            m.put("type", s.getType());
            m.put("name", s.getName());
            m.put("latencyMs", s.getLatencyMs());
            m.put("payload", s.getPayload());
            return m;
        }).toList());
        return out;
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
