package com.rappi.buyer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "agent_runs")
@Getter
@Setter
@NoArgsConstructor
public class AgentRun {

    public enum Status { RUNNING, COMPLETED, NEEDS_APPROVAL, ESCALATED, FAILED }

    public enum Decision { ACCEPT, MODIFY, REJECT, INVESTIGATE, ESCALATE }

    @Id
    @Column(columnDefinition = "char(36)")
    private String id;

    private String scenario;
    private String sku;
    private String nodeId;

    /** The situation handed to the agent. */
    @JdbcTypeCode(SqlTypes.JSON)
    private String input;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Enumerated(EnumType.STRING)
    private Decision decision;

    private Integer finalQty;

    @Column(columnDefinition = "text")
    private String explanation;

    @JdbcTypeCode(SqlTypes.JSON)
    private String validationReport;

    private int tokensIn;
    private int tokensOut;
    private int llmCalls;
    private Long durationMs;
    private Instant createdAt;
}
