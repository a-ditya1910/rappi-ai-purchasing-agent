package com.rappi.buyer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@IdClass(DemandForecast.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class DemandForecast {

    @Id
    private String nodeId;
    @Id
    private String sku;
    @Id
    private LocalDate forecastDate;

    private BigDecimal forecastUnits;
    private BigDecimal forecastStd;
    private String modelVersion;
    private Instant generatedAt;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Key implements Serializable {
        private String nodeId;
        private String sku;
        private LocalDate forecastDate;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return nodeId.equals(k.nodeId) && sku.equals(k.sku) && forecastDate.equals(k.forecastDate);
        }

        @Override
        public int hashCode() {
            return (nodeId.hashCode() * 31 + sku.hashCode()) * 31 + forecastDate.hashCode();
        }
    }
}
