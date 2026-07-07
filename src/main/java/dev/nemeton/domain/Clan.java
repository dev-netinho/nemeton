package dev.nemeton.domain;

import java.time.Instant;
import java.util.*;

public final class Clan {
    private final UUID id;
    private String name;
    private final String tag;
    private UUID owner;
    private WarState warState;
    private Instant warChangedAt;
    private Instant warLockedUntil;
    private int cofferDiamonds;
    private BlockPoint coffer;
    private final Map<UUID, ClanRole> members = new HashMap<>();
    private final Set<ChunkPos> claims = new HashSet<>();
    private String discordRoleId;
    private String discordTextId;
    private String discordVoiceId;

    public Clan(UUID id, String name, String tag, UUID owner) {
        this(id, name, tag, owner, WarState.ACTIVE, Instant.now(), null, 0, null);
    }

    public Clan(UUID id, String name, String tag, UUID owner, WarState warState,
                Instant warChangedAt, Instant warLockedUntil, int cofferDiamonds, BlockPoint coffer) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.tag = Objects.requireNonNull(tag).toUpperCase(Locale.ROOT);
        this.owner = Objects.requireNonNull(owner);
        this.warState = Objects.requireNonNull(warState);
        this.warChangedAt = warChangedAt;
        this.warLockedUntil = warLockedUntil;
        this.cofferDiamonds = cofferDiamonds;
        this.coffer = coffer;
        members.put(owner, ClanRole.LEADER);
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public String tag() { return tag; }
    public UUID owner() { return owner; }
    public WarState warState() { return warState; }
    public Instant warChangedAt() { return warChangedAt; }
    public Instant warLockedUntil() { return warLockedUntil; }
    public int cofferDiamonds() { return cofferDiamonds; }
    public BlockPoint coffer() { return coffer; }
    public Map<UUID, ClanRole> members() { return Collections.unmodifiableMap(members); }
    public Set<ChunkPos> claims() { return Collections.unmodifiableSet(claims); }
    public String discordRoleId() { return discordRoleId; }
    public String discordTextId() { return discordTextId; }
    public String discordVoiceId() { return discordVoiceId; }
    public ClanRole roleOf(UUID player) { return members.get(player); }
    public boolean contains(UUID player) { return members.containsKey(player); }

    public void addMember(UUID player, ClanRole role) { members.put(player, role); }
    public void removeMember(UUID player) { members.remove(player); }
    public void setRole(UUID player, ClanRole role) { members.put(player, role); }
    public void addClaim(ChunkPos claim) { claims.add(claim); }
    public void removeClaim(ChunkPos claim) { claims.remove(claim); }
    public void setWar(WarState state, Instant changedAt, Instant lockedUntil) {
        this.warState = state; this.warChangedAt = changedAt; this.warLockedUntil = lockedUntil;
    }
    public void deposit(int diamonds) { cofferDiamonds = Math.addExact(cofferDiamonds, diamonds); }
    public void withdraw(int diamonds) {
        if (diamonds < 0 || cofferDiamonds < diamonds) throw new IllegalArgumentException("Saldo insuficiente no cofre.");
        cofferDiamonds -= diamonds;
    }
    public void setCoffer(BlockPoint point) { this.coffer = point; }
    public void setDiscord(String roleId, String textId, String voiceId) {
        this.discordRoleId = roleId; this.discordTextId = textId; this.discordVoiceId = voiceId;
    }

    public record BlockPoint(String world, int x, int y, int z) {}
}
