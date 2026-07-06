package dev.nemeton.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public final class NemetonRules {
    private NemetonRules() {}

    public static int clanClaimLimit(int members, int base, int perMember, int maximum,
                                     int warBonusPercent, boolean warActive) {
        int normal = Math.min(maximum, base + perMember * members);
        return warActive ? Math.min(maximum, normal + (normal * warBonusPercent / 100)) : normal;
    }

    public static boolean isConnected(Set<ChunkPos> existing, ChunkPos candidate) {
        return existing.isEmpty() || existing.stream().anyMatch(candidate::adjacentTo);
    }

    public static void validateRaidSlots(List<Instant> slots, Instant now, Duration minimum, Duration maximum) {
        if (slots.size() != 3 || new HashSet<>(slots).size() != 3) {
            throw new IllegalArgumentException("Informe três horários diferentes.");
        }
        Instant earliest = now.plus(minimum);
        Instant latest = now.plus(maximum);
        if (slots.stream().anyMatch(slot -> slot.isBefore(earliest) || slot.isAfter(latest))) {
            throw new IllegalArgumentException("Os horários devem estar dentro da janela permitida.");
        }
        if (!slots.equals(slots.stream().sorted().toList())) {
            throw new IllegalArgumentException("Os horários precisam estar em ordem crescente.");
        }
    }
}

