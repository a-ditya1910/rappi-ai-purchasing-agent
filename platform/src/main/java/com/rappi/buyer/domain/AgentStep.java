package com.rappi.buyer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;

/**
 * One line of the audit trail. Written by TraceInterceptor for every tool call
 * the agent makes, and by the agent itself for its own reasoning steps.
 *
 * This single table is the SSE stream, the trace UI, the eval tool-coverage
 * assertions and the llm observability story. Four requirements, one table,
 * because tool calls happen to be http requests.
 */
@Entity
@Table(name = "agent_steps")
@IdClass(AgentStep.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class AgentStep {

    public enum Type { THOUGHT, TOOL_CALL, TOOL_RESULT, VALIDATION, DECISION, ERROR, REPAIR }

    @Id
    @Column(columnDefinition = "char(36)")
    private String runId;

    @Id
    private int seq;

    @Enumerated(EnumType.STRING)
    private Type type;

    /** Endpoint path for tool calls, node name for agent steps. */
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    private Integer latencyMs;
    private Integer tokens;
    private Instant createdAt;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Key implements Serializable {
        private String runId;
        private int seq;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return seq == k.seq && runId.equals(k.runId);
        }

        @Override
        public int hashCode() {
            return runId.hashCode() * 31 + seq;
        }
    }
}
