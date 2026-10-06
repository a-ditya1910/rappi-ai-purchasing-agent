package com.rappi.buyer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One message from a supplier about a purchase order, as read by the agent and
 * checked by the platform. Kept whether or not it was applied - a refused
 * message is exactly the one somebody will ask about later.
 */
@Entity
@Table(name = "supplier_events")
@Getter
@Setter
@NoArgsConstructor
public class SupplierEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String poId;

    /** CONFIRM, PARTIAL, DELAY, PRICE_CHANGE or OTHER. */
    private String type;

    /** The structured reading the agent extracted. */
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    /** sha-256 of the raw message, part of the unique key that stops a resend being applied twice. */
    @Column(columnDefinition = "char(64)")
    private String payloadHash;

    private Instant receivedAt;
    private String sender;

    @Column(columnDefinition = "char(36)")
    private String agentRunId;

    @Column(columnDefinition = "text")
    private String rawText;

    private boolean applied;
    private String note;
}
