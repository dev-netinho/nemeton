package dev.nemeton.service;

import dev.nemeton.config.Settings;
import dev.nemeton.domain.*;
import dev.nemeton.integration.RegionGateway;
import dev.nemeton.persistence.NemetonRepository;
import dev.nemeton.state.ServerState;
import org.bukkit.Location;

import java.util.*;

public final class ClaimService {
    private final ServerState state; private final NemetonRepository repository; private final RegionGateway regions;
    private final ClanService clans; private final Settings settings;
    private AllianceService alliances;
    public ClaimService(ServerState state, NemetonRepository repository, RegionGateway regions, ClanService clans, Settings settings) {
        this.state = state; this.repository = repository; this.regions = regions; this.clans = clans; this.settings = settings;
    }
    public void setAllianceService(AllianceService alliances) { this.alliances = alliances; }
    public void claim(Clan clan, UUID actor, ChunkPos chunk) {
        clans.requireManager(clan, actor); ensureOutsideHub(chunk);
        if (state.clanAt(chunk).isPresent() || state.sanctuaryOwner(chunk).isPresent()) throw new IllegalArgumentException("Este chunk já está protegido.");
        if (clan.claims().size() >= clans.claimLimit(clan)) throw new IllegalArgumentException("O clã atingiu o limite de claims.");
        if (!NemetonRules.isConnected(clan.claims(), chunk)) throw new IllegalArgumentException("O claim deve tocar outro território do clã.");
        repository.addClaim(clan.id(), chunk); clan.addClaim(chunk); state.indexClaim(chunk, clan.id()); regions.createClanClaim(chunk, clan.members().keySet());
    }
    public void unclaim(Clan clan, UUID actor, ChunkPos chunk) {
        clans.requireManager(clan, actor); if (!clan.claims().contains(chunk)) throw new IllegalArgumentException("Este chunk não pertence ao clã.");
        if (clan.coffer() != null && clan.coffer().world().equals(chunk.world()) && clan.coffer().x() >> 4 == chunk.x() && clan.coffer().z() >> 4 == chunk.z()) throw new IllegalArgumentException("Mova o cofre antes de remover este claim.");
        Set<ChunkPos> remaining = new HashSet<>(clan.claims()); remaining.remove(chunk);
        if (!connected(remaining)) throw new IllegalArgumentException("Isso dividiria o território do clã.");
        repository.removeClaim(chunk); clan.removeClaim(chunk); state.unindexClaim(chunk); regions.removeClanClaim(chunk);
    }
    public void sanctuary(UUID owner, ChunkPos chunk) {
        ensureOutsideHub(chunk); Set<ChunkPos> existing = state.sanctuariesOf(owner);
        if (existing.size() >= settings.claims().sanctuaryLimit()) throw new IllegalArgumentException("Você atingiu o limite do santuário.");
        if (state.clanAt(chunk).isPresent() || state.sanctuaryOwner(chunk).isPresent()) throw new IllegalArgumentException("Este chunk já está protegido.");
        if (!NemetonRules.isConnected(existing, chunk)) throw new IllegalArgumentException("O santuário deve ser conectado.");
        repository.addSanctuary(owner, chunk); state.addSanctuary(chunk, owner); regions.createSanctuary(chunk, owner, Set.of());
    }
    public void removeSanctuary(UUID owner, ChunkPos chunk) {
        if (!state.sanctuaryOwner(chunk).filter(owner::equals).isPresent()) throw new IllegalArgumentException("Este chunk não é seu santuário.");
        Set<ChunkPos> remaining = state.sanctuariesOf(owner); remaining.remove(chunk); if (!connected(remaining)) throw new IllegalArgumentException("Isso dividiria seu santuário.");
        repository.removeSanctuary(chunk); state.removeSanctuary(chunk); regions.removeSanctuary(chunk, owner);
    }
    public void trust(UUID owner, UUID trusted, boolean add) {
        if (add) { repository.trustSanctuary(owner, trusted); state.trustSanctuary(owner, trusted); }
        else { repository.untrustSanctuary(owner, trusted); state.untrustSanctuary(owner, trusted); }
        for (ChunkPos chunk : state.sanctuariesOf(owner)) regions.createSanctuary(chunk, owner, state.sanctuaryTrustedPlayers(owner));
    }
    public boolean canAccess(UUID player, ChunkPos chunk) {
        Optional<UUID> sanctuary = state.sanctuaryOwner(chunk); if (sanctuary.isPresent()) return state.sanctuaryTrusted(sanctuary.get(), player);
        Optional<Clan> clan = state.clanAt(chunk);
        if (clan.isEmpty() || clan.get().contains(player) || state.activeRaidAt(chunk).filter(r -> r.participants().containsKey(player)).isPresent()) return true;
        return alliances != null && state.clanOf(player).map(visitor -> alliances.grantsAccess(clan.get().id(), visitor.id())).orElse(false);
    }
    public boolean isHub(Location location) {
        if (!location.getWorld().getName().equals(settings.hub().world())) return false;
        double dx = location.getX() - settings.hub().x(), dz = location.getZ() - settings.hub().z(); return dx * dx + dz * dz <= settings.hub().radius() * settings.hub().radius();
    }
    private void ensureOutsideHub(ChunkPos chunk) {
        if (!chunk.world().equals(settings.hub().world())) throw new IllegalArgumentException("Claims só são permitidos no Overworld principal.");
        int centerX = ((int) settings.hub().x()) >> 4, centerZ = ((int) settings.hub().z()) >> 4, radius = settings.hub().radius() / 16 + 1;
        if (Math.abs(chunk.x() - centerX) <= radius && Math.abs(chunk.z() - centerZ) <= radius) throw new IllegalArgumentException("Não é possível proteger terreno perto do Nemeton.");
    }
    private boolean connected(Set<ChunkPos> chunks) {
        if (chunks.isEmpty()) return true; Set<ChunkPos> seen = new HashSet<>(); Deque<ChunkPos> queue = new ArrayDeque<>(); queue.add(chunks.iterator().next());
        while (!queue.isEmpty()) { ChunkPos current = queue.remove(); if (!seen.add(current)) continue; chunks.stream().filter(current::adjacentTo).filter(c -> !seen.contains(c)).forEach(queue::add); }
        return seen.size() == chunks.size();
    }
}
