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

/**
 * A decision the agent reached but is not allowed to act on.
 *
 * The proposed action is stored whole, which is what makes resuming cheap: the
 * buyer approves, and execution picks up the exact action that was queued
 * rather than asking the model to think again and possibly land somewhere else.
 */
@Entity
@Table(name = "approvals")
@Getter
@Setter
@NoArgsConstructor
public class Approval {

    public enum Status { PENDING, APPROVED, REJECTED, EXPIRED }

    @Id
    @Column(columnDefinition = "char(36)")
    private String id;

    @Column(columnDefinition = "char(36)")
    private String runId;

    @JdbcTypeCode(SqlTypes.JSON)
    private String proposedAction;

    @Column(columnDefinition = "text")
    private String reason;

    private String riskTier;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String decidedBy;
    private Instant decidedAt;

    @Column(columnDefinition = "text")
    private String decisionNote;

    private Instant createdAt;
}
