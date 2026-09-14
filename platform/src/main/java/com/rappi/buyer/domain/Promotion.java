package com.rappi.buyer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;

@Entity
@Table(name = "promotions")
@IdClass(Promotion.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class Promotion {

    @Id
    private String promoId;
    @Id
    private String sku;
    @Id
    private String nodeId;

    private LocalDate startDate;
    private LocalDate endDate;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Key implements Serializable {
        private String promoId;
        private String sku;
        private String nodeId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return promoId.equals(k.promoId) && sku.equals(k.sku) && nodeId.equals(k.nodeId);
        }

        @Override
        public int hashCode() {
            return (promoId.hashCode() * 31 + sku.hashCode()) * 31 + nodeId.hashCode();
        }
    }
}
