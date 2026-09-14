package com.rappi.buyer.domain;

public enum PoStatus {
    DRAFT,
    PENDING_APPROVAL,
    SUBMITTED,
    CONFIRMED,
    PARTIALLY_CONFIRMED,
    RECEIVED,
    CANCELLED;

    public boolean isOpen() {
        return this == SUBMITTED || this == CONFIRMED || this == PARTIALLY_CONFIRMED;
    }
}
