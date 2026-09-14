package com.rappi.buyer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "suppliers")
@Getter
@Setter
@NoArgsConstructor
public class Supplier {

    @Id
    private String id;

    private String name;
    @Column(columnDefinition = "char(2)")
    private String country;
    private int leadTimeDays;
    private BigDecimal leadTimeStd;
    private int moqUnits;
    private BigDecimal reliabilityScore;
    private boolean isActive;
    private int paymentTermsDays;
}
