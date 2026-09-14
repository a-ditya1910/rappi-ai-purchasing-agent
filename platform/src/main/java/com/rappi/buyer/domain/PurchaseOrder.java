package com.rappi.buyer.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "purchase_orders")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseOrder {

    @Id
    private String id;

    private String nodeId;
    private String supplierId;

    @Enumerated(EnumType.STRING)
    private PoStatus status;

    private LocalDate expectedDelivery;
    private BigDecimal totalValue;
    private String createdBy;
    private Instant createdAt;

    /** Optimistic locking. amend-po passes the version it expects to be changing. */
    @Version
    private int version;

    /** Unique in the db, so a retried create returns the original instead of a duplicate. */
    private String idempotencyKey;

    @Column(columnDefinition = "char(36)")
    private String agentRunId;

    /*
     * Lines are part of the PO aggregate - a PO without lines is meaningless, so
     * they load and save together. Everything else holds a plain id and gets looked
     * up through its own repository, which keeps open-in-view=false honest.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "po_id")
    private List<PoLine> lines = new ArrayList<>();
}
