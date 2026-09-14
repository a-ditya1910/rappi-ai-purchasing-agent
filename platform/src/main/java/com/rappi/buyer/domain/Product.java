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
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
public class Product {

    @Id
    private String sku;

    private String name;
    private String category;
    private BigDecimal unitCost;
    private int casePack;
    private int unitVolumeCm3;
    private int shelfLifeDays;
    private boolean isPerishable;
    @Column(columnDefinition = "char(1)")
    private String abcClass;
}
