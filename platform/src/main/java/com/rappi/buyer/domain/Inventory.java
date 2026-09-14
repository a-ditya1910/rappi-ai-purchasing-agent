package com.rappi.buyer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;

@Entity
@IdClass(Inventory.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class Inventory {

    @Id
    private String nodeId;
    @Id
    private String sku;

    private int onHand;
    private int reserved;
    private int inTransit;
    private Instant updatedAt;

    /** What you can actually sell. Never use onHand on its own. */
    public int available() {
        return Math.max(0, onHand - reserved);
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Key implements Serializable {
        private String nodeId;
        private String sku;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return nodeId.equals(k.nodeId) && sku.equals(k.sku);
        }

        @Override
        public int hashCode() {
            return nodeId.hashCode() * 31 + sku.hashCode();
        }
    }
}
