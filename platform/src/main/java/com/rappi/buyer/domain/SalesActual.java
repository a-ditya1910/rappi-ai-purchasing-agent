package com.rappi.buyer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;

@Entity
@Table(name = "sales_actuals")
@IdClass(SalesActual.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class SalesActual {

    @Id
    private String nodeId;
    @Id
    private String sku;
    @Id
    private LocalDate saleDate;

    private int unitsSold;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Key implements Serializable {
        private String nodeId;
        private String sku;
        private LocalDate saleDate;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return nodeId.equals(k.nodeId) && sku.equals(k.sku) && saleDate.equals(k.saleDate);
        }

        @Override
        public int hashCode() {
            return (nodeId.hashCode() * 31 + sku.hashCode()) * 31 + saleDate.hashCode();
        }
    }
}
