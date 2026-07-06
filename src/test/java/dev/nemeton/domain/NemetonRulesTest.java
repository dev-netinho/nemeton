package dev.nemeton.domain;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class NemetonRulesTest {
    @Test void calculatesClaimLimitAndWarBonusWithoutPassingCap() {
        assertThat(NemetonRules.clanClaimLimit(5, 6, 4, 50, 25, false)).isEqualTo(26);
        assertThat(NemetonRules.clanClaimLimit(5, 6, 4, 50, 25, true)).isEqualTo(32);
        assertThat(NemetonRules.clanClaimLimit(20, 6, 4, 50, 25, true)).isEqualTo(50);
    }
    @Test void onlyAcceptsAdjacentClaimsInSameWorld() {
        Set<ChunkPos> claims = Set.of(new ChunkPos("world", 2, 3));
        assertThat(NemetonRules.isConnected(claims, new ChunkPos("world", 3, 3))).isTrue();
        assertThat(NemetonRules.isConnected(claims, new ChunkPos("world", 4, 3))).isFalse();
        assertThat(NemetonRules.isConnected(claims, new ChunkPos("nether", 3, 3))).isFalse();
    }
    @Test void validatesThreeOrderedRaidSlotsInsideWindow() {
        Instant now = Instant.parse("2026-07-06T12:00:00Z");
        List<Instant> valid = List.of(now.plus(Duration.ofHours(24)), now.plus(Duration.ofHours(36)), now.plus(Duration.ofHours(72)));
        assertThatCode(() -> NemetonRules.validateRaidSlots(valid, now, Duration.ofHours(24), Duration.ofHours(72))).doesNotThrowAnyException();
        assertThatThrownBy(() -> NemetonRules.validateRaidSlots(valid.reversed(), now, Duration.ofHours(24), Duration.ofHours(72))).isInstanceOf(IllegalArgumentException.class);
    }
}

