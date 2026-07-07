package dev.nemeton.state;

import dev.nemeton.domain.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerState {
    private final Map<UUID, Clan> clans = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> clanByPlayer = new ConcurrentHashMap<>();
    private final Map<ChunkPos, UUID> clanByChunk = new ConcurrentHashMap<>();
    private final Map<ChunkPos, UUID> sanctuaryByChunk = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> sanctuaryTrust = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> clanTrust = new ConcurrentHashMap<>();
    private final Map<UUID, Raid> raids = new ConcurrentHashMap<>();
    private final Map<String, Alliance> alliances = new ConcurrentHashMap<>();

    public Collection<Clan> clans() { return Collections.unmodifiableCollection(clans.values()); }
    public Collection<Raid> raids() { return Collections.unmodifiableCollection(raids.values()); }
    public Collection<Alliance> alliances() { return Collections.unmodifiableCollection(alliances.values()); }
    public Optional<Clan> clan(UUID id) { return Optional.ofNullable(clans.get(id)); }
    public Optional<Clan> clanOf(UUID player) { return Optional.ofNullable(clanByPlayer.get(player)).flatMap(this::clan); }
    public Optional<Clan> clanAt(ChunkPos chunk) { return Optional.ofNullable(clanByChunk.get(chunk)).flatMap(this::clan); }
    public Optional<Clan> clanByTag(String tag) { return clans.values().stream().filter(c -> c.tag().equalsIgnoreCase(tag)).findFirst(); }
    public Optional<UUID> sanctuaryOwner(ChunkPos chunk) { return Optional.ofNullable(sanctuaryByChunk.get(chunk)); }
    public Set<ChunkPos> sanctuariesOf(UUID owner) {
        Set<ChunkPos> found = new HashSet<>();
        sanctuaryByChunk.forEach((chunk, uuid) -> { if (uuid.equals(owner)) found.add(chunk); });
        return found;
    }
    public Map<ChunkPos, UUID> sanctuaries() { return Map.copyOf(sanctuaryByChunk); }
    public Set<UUID> sanctuaryTrustedPlayers(UUID owner) { return Set.copyOf(sanctuaryTrust.getOrDefault(owner, Set.of())); }
    public boolean sanctuaryTrusted(UUID owner, UUID player) {
        return owner.equals(player) || sanctuaryTrust.getOrDefault(owner, Set.of()).contains(player);
    }
    public Set<UUID> clanTrustedPlayers(UUID clan) { return Set.copyOf(clanTrust.getOrDefault(clan, Set.of())); }
    public boolean clanTrusted(UUID clan, UUID player) { return clanTrust.getOrDefault(clan, Set.of()).contains(player); }
    public Optional<Raid> raid(UUID id) { return Optional.ofNullable(raids.get(id)); }
    public Optional<Alliance> alliance(UUID first, UUID second) { return Optional.ofNullable(alliances.get(allianceKey(first, second))); }
    public Optional<Raid> activeRaidForClan(UUID clanId) {
        return raids.values().stream().filter(r -> !r.state().terminal() && (r.attackerId().equals(clanId) || r.defenderId().equals(clanId))).findFirst();
    }
    public Optional<Raid> activeRaidAt(ChunkPos chunk) {
        return clanAt(chunk).flatMap(clan -> raids.values().stream()
                .filter(r -> r.state() == RaidState.ACTIVE && r.defenderId().equals(clan.id())).findFirst());
    }
    public void addClan(Clan clan) {
        clans.put(clan.id(), clan);
        clan.members().keySet().forEach(player -> clanByPlayer.put(player, clan.id()));
        clan.claims().forEach(chunk -> clanByChunk.put(chunk, clan.id()));
    }
    public void removeClan(UUID clanId) {
        Clan clan = clans.remove(clanId);
        if (clan != null) {
            clan.members().keySet().forEach(clanByPlayer::remove);
            clan.claims().forEach(clanByChunk::remove);
        }
    }
    public void indexMember(UUID player, UUID clan) { clanByPlayer.put(player, clan); }
    public void unindexMember(UUID player) { clanByPlayer.remove(player); }
    public void indexClaim(ChunkPos chunk, UUID clan) { clanByChunk.put(chunk, clan); }
    public void unindexClaim(ChunkPos chunk) { clanByChunk.remove(chunk); }
    public void addSanctuary(ChunkPos chunk, UUID owner) { sanctuaryByChunk.put(chunk, owner); }
    public void removeSanctuary(ChunkPos chunk) { sanctuaryByChunk.remove(chunk); }
    public void trustSanctuary(UUID owner, UUID trusted) { sanctuaryTrust.computeIfAbsent(owner, ignored -> ConcurrentHashMap.newKeySet()).add(trusted); }
    public void untrustSanctuary(UUID owner, UUID trusted) { sanctuaryTrust.getOrDefault(owner, Set.of()).remove(trusted); }
    public void trustClan(UUID clan, UUID trusted) { clanTrust.computeIfAbsent(clan, ignored -> ConcurrentHashMap.newKeySet()).add(trusted); }
    public void untrustClan(UUID clan, UUID trusted) { clanTrust.getOrDefault(clan, Set.of()).remove(trusted); }
    public void addRaid(Raid raid) { raids.put(raid.id(), raid); }
    public void putAlliance(Alliance alliance) { alliances.put(allianceKey(alliance.clanA(), alliance.clanB()), alliance); }
    public void removeAlliance(UUID first, UUID second) { alliances.remove(allianceKey(first, second)); }
    private String allianceKey(UUID first, UUID second) { return first.compareTo(second) < 0 ? first + ":" + second : second + ":" + first; }
}
