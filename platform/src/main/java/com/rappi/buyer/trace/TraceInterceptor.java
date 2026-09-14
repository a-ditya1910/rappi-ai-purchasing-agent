package com.rappi.buyer.trace;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Writes an agent_steps row for every tool call. The agent itself contains no
 * tracing code at all - its tool calls are http requests, so one interceptor on
 * /tools/** captures the lot.
 *
 * Reads without an X-Run-Id are allowed through untraced so the endpoints stay
 * usable from curl and the ui. Writes without one are refused: an action nobody
 * can audit later is not an action we are willing to take.
 */
@Component
public class TraceInterceptor implements HandlerInterceptor {

    static final String RUN_ID = "X-Run-Id";
    private static final String STARTED = "trace.started";
    private static final Logger log = LoggerFactory.getLogger(TraceInterceptor.class);

    private final TraceWriter writer;

    public TraceInterceptor(TraceWriter writer) {
        this.writer = writer;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        req.setAttribute(STARTED, System.nanoTime());

        if (isWrite(req) && isBlank(req.getHeader(RUN_ID))) {
            log.warn("refusing untraced write to {}", req.getRequestURI());
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            res.setContentType("application/json");
            try {
                res.getWriter().write("""
                        {"ok":false,"error":"MISSING_RUN_ID",\
                        "detail":"writes must carry an X-Run-Id header so they can be audited"}""");
            } catch (Exception e) {
                log.warn("could not write the rejection body", e);
            }
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler,
                                Exception ex) {
        String runId = req.getHeader(RUN_ID);
        if (isBlank(runId)) {
            return;
        }
        Long started = (Long) req.getAttribute(STARTED);
        int latencyMs = started == null ? 0 : (int) ((System.nanoTime() - started) / 1_000_000);

        // tracing must never be the reason a tool call fails
        try {
            writer.record(runId, req.getMethod(), req.getRequestURI(),
                    req.getQueryString(), res.getStatus(), latencyMs, ex);
        } catch (Exception e) {
            log.warn("could not write trace step for run {}", runId, e);
        }
    }

    private static boolean isWrite(HttpServletRequest req) {
        String m = req.getMethod();
        // compute endpoints are POSTs but change nothing, so they are not writes
        if ("POST".equals(m) && (req.getRequestURI().endsWith("/calculate-reorder")
                || req.getRequestURI().endsWith("/validate-purchase"))) {
            return false;
        }
        return "POST".equals(m) || "PUT".equals(m) || "PATCH".equals(m) || "DELETE".equals(m);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
