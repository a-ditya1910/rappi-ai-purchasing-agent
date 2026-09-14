package com.rappi.buyer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "po_lines")
@Getter
@Setter
@NoArgsConstructor
public class PoLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sku;
    private int qtyOrdered;

    /** Null until the supplier responds. Differing from qtyOrdered is the scenario 2 trigger. */
    private Integer qtyConfirmed;
    private Integer qtyReceived;

    private BigDecimal unitPrice;
}
