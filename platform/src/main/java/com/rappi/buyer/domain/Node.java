package com.rappi.buyer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "nodes")
@Getter
@Setter
@NoArgsConstructor
public class Node {

    @Id
    private String id;

    private String name;
    @Column(columnDefinition = "char(2)")
    private String country;
    private String type;
    private long storageCapacityCm3;
    private long storageUsedCm3;
    private int reviewPeriodDays;

    public long freeStorageCm3() {
        return storageCapacityCm3 - storageUsedCm3;
    }
}
