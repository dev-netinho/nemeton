package dev.nemeton.domain;

public enum RaidState {
    DECLARED, SCHEDULED, ACTIVE, RESTORING, COMPLETED, CANCELLED, RECOVERY;

    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}

