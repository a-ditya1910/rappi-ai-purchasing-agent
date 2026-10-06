package com.rappi.buyer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Stock moving from one dark store to another. Leaves the sender's shelf the
 * moment it is created, and counts as incoming at the receiver until it lands.
 */
@Entity
@Table(name = "transfer_orders")
@Getter
@Setter
@NoArgsConstructor
public class TransferOrder {

    public enum Status { IN_TRANSIT, RECEIVED, CANCELLED }

    @Id
    private String id;

    private String fromNode;
    private String toNode;
    private String sku;
    private int qty;

    @Enumerated(EnumType.STRING)
    private Status status;

    private LocalDate expectedArrival;
    private String idempotencyKey;

    @Column(columnDefinition = "char(36)")
    private String approvalId;

    @Column(columnDefinition = "char(36)")
    private String agentRunId;

    private Instant createdAt;

    @Version
    private int version;
}
