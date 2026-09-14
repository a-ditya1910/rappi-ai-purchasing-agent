package com.rappi.buyer.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rappi.buyer.domain.AgentStep;
import com.rappi.buyer.repo.AgentRunRepo;
import com.rappi.buyer.repo.AgentStepRepo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TraceWriter {

    private static final Logger log = LoggerFactory.getLogger(TraceWriter.class);

    private final AgentRunRepo runs;
    private final AgentStepRepo steps;
    private final ObjectMapper json;
    private final Clock clock;

    public TraceWriter(AgentRunRepo runs, AgentStepRepo steps, ObjectMapper json, Clock clock) {
        this.runs = runs;
        this.steps = steps;
        this.json = json;
        this.clock = clock;
    }

    /**
     * REQUIRES_NEW so the trace survives even when the tool call it describes rolls
     * back. A failed action we cannot see afterwards is worse than a failed action.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String runId, String method, String path, String query,
                       int httpStatus, int latencyMs, Exception ex) {

        if (!runs.existsById(runId)) {
            // agent ran with an id we never opened a run for. worth knowing, not
            // worth an FK violation that fails the tool call.
            log.debug("skipping trace, no run {}", runId);
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("method", method);
        payload.put("path", path);
        if (query != null) {
            payload.put("query", query);
        }
        payload.put("status", httpStatus);
        if (ex != null) {
            payload.put("exception", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }

        AgentStep step = new AgentStep();
        step.setRunId(runId);
        step.setSeq(steps.nextSeq(runId));
        step.setType(httpStatus >= 400 || ex != null ? AgentStep.Type.ERROR : AgentStep.Type.TOOL_CALL);
        step.setName(path);
        step.setPayload(write(payload));
        step.setLatencyMs(latencyMs);
        step.setCreatedAt(Instant.now(clock));
        steps.save(step);
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"error\":\"could not serialise payload\"}";
        }
    }
}
