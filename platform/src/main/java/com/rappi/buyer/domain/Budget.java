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
@Table(name = "budgets")
@IdClass(Budget.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class Budget {

    @Id
    private String nodeId;
    @Id
    private String category;
    @Id
    @Column(columnDefinition = "char(7)")
    private String period;

    private BigDecimal allocated;
    private BigDecimal committed;
    private BigDecimal spent;

    public BigDecimal available() {
        return allocated.subtract(committed).subtract(spent);
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Key implements Serializable {
        private String nodeId;
        private String category;
        private String period;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return nodeId.equals(k.nodeId) && category.equals(k.category) && period.equals(k.period);
        }

        @Override
        public int hashCode() {
            return (nodeId.hashCode() * 31 + category.hashCode()) * 31 + period.hashCode();
        }
    }
}
