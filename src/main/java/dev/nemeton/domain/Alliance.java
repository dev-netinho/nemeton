package dev.nemeton.domain;

import java.time.Instant;
import java.util.UUID;

public record Alliance(UUID clanA, UUID clanB, Status status, boolean accessGranted, Instant truceUntil) {
    public enum Status { PENDING, ACTIVE, TRUCE }
    public boolean includes(UUID clan) { return clanA.equals(clan) || clanB.equals(clan); }
    public UUID other(UUID clan) { return clanA.equals(clan) ? clanB : clanA; }
}

