package com.rappi.buyer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;

@Entity
@Table(name = "supplier_products")
@IdClass(SupplierProduct.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class SupplierProduct {

    @Id
    private String supplierId;
    @Id
    private String sku;

    private BigDecimal unitPrice;
    @Column(columnDefinition = "char(3)")
    private String currency;
    private int minOrderUnits;
    private int maxDailyCapacity;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Key implements Serializable {
        private String supplierId;
        private String sku;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return supplierId.equals(k.supplierId) && sku.equals(k.sku);
        }

        @Override
        public int hashCode() {
            return supplierId.hashCode() * 31 + sku.hashCode();
        }
    }
}
