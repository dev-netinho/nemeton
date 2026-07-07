package dev.nemeton.service;

import dev.nemeton.config.Settings;
import dev.nemeton.domain.*;
import dev.nemeton.persistence.NemetonRepository;
import dev.nemeton.integration.RegionGateway;
import dev.nemeton.state.ServerState;

import java.time.Instant;
import java.util.UUID;

public final class AllianceService {
    private final ServerState state; private final NemetonRepository repository; private final ClanService clans; private final Settings settings; private final RegionGateway regions;
    public AllianceService(ServerState state, NemetonRepository repository, ClanService clans, Settings settings, RegionGateway regions) { this.state = state; this.repository = repository; this.clans = clans; this.settings = settings; this.regions = regions; }

    public Alliance requestOrAccept(Clan source, Clan target, UUID actor) {
        clans.requireManager(source, actor); if (source.id().equals(target.id())) throw new IllegalArgumentException("Aliança inválida.");
        Alliance existing = state.alliance(source.id(), target.id()).orElse(null);
        if (existing == null) {
            Alliance pending = new Alliance(source.id(), target.id(), Alliance.Status.PENDING, false, null); state.putAlliance(pending); repository.saveAlliance(pending); return pending;
        }
        if (existing.status() == Alliance.Status.PENDING && existing.clanB().equals(source.id())) {
            Alliance active = new Alliance(existing.clanA(), existing.clanB(), Alliance.Status.ACTIVE, false, null); state.putAlliance(active); repository.saveAlliance(active); return active;
        }
        throw new IllegalArgumentException(existing.status() == Alliance.Status.ACTIVE ? "Os clãs já são aliados." : "Ainda existe uma trégua entre os clãs.");
    }
    public Alliance breakAlliance(Clan source, Clan target, UUID actor) {
        clans.requireManager(source, actor); Alliance existing = state.alliance(source.id(), target.id()).orElseThrow(() -> new IllegalArgumentException("Não existe aliança."));
        Alliance truce = new Alliance(existing.clanA(), existing.clanB(), Alliance.Status.TRUCE, false, Instant.now().plus(settings.war().truce())); state.putAlliance(truce); repository.saveAlliance(truce);
        regions.syncClanMembers(source.claims(), clans.claimAccessors(source)); regions.syncClanMembers(target.claims(), clans.claimAccessors(target)); return truce;
    }
    public Alliance setAccess(Clan source, Clan target, UUID actor, boolean allowed) {
        clans.requireManager(source, actor); Alliance existing = state.alliance(source.id(), target.id()).filter(a -> a.status() == Alliance.Status.ACTIVE).orElseThrow(() -> new IllegalArgumentException("Os clãs não possuem aliança ativa."));
        Alliance updated = new Alliance(existing.clanA(), existing.clanB(), existing.status(), allowed, null); state.putAlliance(updated); repository.saveAlliance(updated);
        java.util.Set<UUID> combined = clans.claimAccessors(source); combined.addAll(clans.claimAccessors(target));
        regions.syncClanMembers(source.claims(), allowed ? combined : clans.claimAccessors(source)); regions.syncClanMembers(target.claims(), allowed ? combined : clans.claimAccessors(target)); return updated;
    }
    public boolean grantsAccess(UUID ownerClan, UUID visitorClan) { return state.alliance(ownerClan, visitorClan).filter(a -> a.status() == Alliance.Status.ACTIVE && a.accessGranted()).isPresent(); }
    public void reconcileAll() { state.alliances().stream().filter(a -> a.status() == Alliance.Status.ACTIVE && a.accessGranted()).forEach(a -> state.clan(a.clanA()).ifPresent(this::reconcileClan)); }
    public void reconcileClan(Clan changed) {
        state.alliances().stream().filter(a -> a.includes(changed.id()) && a.status() == Alliance.Status.ACTIVE && a.accessGranted()).forEach(alliance -> {
            Clan first = state.clan(alliance.clanA()).orElse(null), second = state.clan(alliance.clanB()).orElse(null); if (first == null || second == null) return;
            java.util.Set<UUID> combined = clans.claimAccessors(first); combined.addAll(clans.claimAccessors(second)); regions.syncClanMembers(first.claims(), combined); regions.syncClanMembers(second.claims(), combined);
        });
    }
    public boolean blocksRaid(UUID first, UUID second) {
        return state.alliance(first, second).filter(a -> a.status() == Alliance.Status.ACTIVE || (a.status() == Alliance.Status.TRUCE && a.truceUntil() != null && a.truceUntil().isAfter(Instant.now()))).isPresent();
    }
}
