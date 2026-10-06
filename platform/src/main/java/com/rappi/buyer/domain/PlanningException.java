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

import java.time.Instant;

/** Something the nightly batch thinks needs attention for one sku at one store. */
@Entity
@Table(name = "planning_exceptions")
@Getter
@Setter
@NoArgsConstructor
public class PlanningException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "char(36)")
    private String batchId;

    private String nodeId;
    private String sku;

    /** DATA_CONFLICT, STOCKOUT_RISK, NEEDS_ORDER, NO_LEGAL_ORDER, DEMAND_SHIFT, STALE_DATA, CANNOT_PLAN */
    private String kind;

    private String detail;
    private Integer suggestedQty;
    private String supplierId;
    private Instant createdAt;
}
