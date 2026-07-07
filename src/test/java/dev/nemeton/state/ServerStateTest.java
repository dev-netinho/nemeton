package dev.nemeton.state;

import dev.nemeton.domain.Clan;
import dev.nemeton.domain.WarState;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ServerStateTest {
    @Test void newClansAreImmediatelyCombatReady() {
        Clan clan = new Clan(UUID.randomUUID(), "Guardiões", "GN", UUID.randomUUID());

        assertThat(clan.warState()).isEqualTo(WarState.ACTIVE);
        assertThat(clan.warLockedUntil()).isNull();
    }

    @Test void sanctuaryAndClanTrustCanBeGrantedAndRevokedIndependently() {
        ServerState state = new ServerState();
        UUID owner = UUID.randomUUID();
        UUID clan = UUID.randomUUID();
        UUID friend = UUID.randomUUID();

        state.trustSanctuary(owner, friend);
        state.trustClan(clan, friend);
        assertThat(state.sanctuaryTrusted(owner, friend)).isTrue();
        assertThat(state.clanTrusted(clan, friend)).isTrue();

        state.untrustSanctuary(owner, friend);
        state.untrustClan(clan, friend);
        assertThat(state.sanctuaryTrusted(owner, friend)).isFalse();
        assertThat(state.clanTrusted(clan, friend)).isFalse();
    }
}
