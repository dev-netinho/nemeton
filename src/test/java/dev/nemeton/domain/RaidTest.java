package dev.nemeton.domain;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RaidTest {
    @Test void captureMustRemainUncontested() {
        Instant now = Instant.now(); Raid raid = new Raid(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 16,
                List.of(now.plusSeconds(1), now.plusSeconds(2), now.plusSeconds(3)), now.plusSeconds(1));
        raid.captureTick(true); raid.captureTick(true); assertThat(raid.captureSeconds()).isEqualTo(2);
        raid.captureTick(false); assertThat(raid.captureSeconds()).isZero();
    }
    @Test void scheduleUsesOneBasedSlot() {
        Instant now = Instant.now(); List<Instant> slots = List.of(now.plusSeconds(1), now.plusSeconds(2), now.plusSeconds(3));
        Raid raid = new Raid(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 16, slots, now);
        raid.schedule(2, slots.get(1).plusSeconds(3600));
        assertThat(raid.state()).isEqualTo(RaidState.SCHEDULED); assertThat(raid.startsAt()).isEqualTo(slots.get(1));
    }
}

