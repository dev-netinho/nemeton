package dev.nemeton.service;

import dev.nemeton.config.Settings;
import dev.nemeton.domain.*;
import dev.nemeton.integration.*;
import dev.nemeton.persistence.NemetonRepository;
import dev.nemeton.state.ServerState;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ClanService {
    private final ServerState state; private final NemetonRepository repository; private final RegionGateway regions;
    private final DiscordBridge discord; private final Settings settings;
    private final Map<UUID, Invite> invites = new ConcurrentHashMap<>();
    private Consumer<Clan> memberChangeHook = ignored -> {};

    public ClanService(ServerState state, NemetonRepository repository, RegionGateway regions, DiscordBridge discord, Settings settings) {
        this.state = state; this.repository = repository; this.regions = regions; this.discord = discord; this.settings = settings;
    }
    public void setMemberChangeHook(Consumer<Clan> hook) { this.memberChangeHook = Objects.requireNonNull(hook); }
    public Clan create(UUID owner, String name, String tag) {
        if (state.clanOf(owner).isPresent()) throw new IllegalArgumentException("Você já pertence a um clã.");
        if (!name.matches("[\\p{L}0-9 _-]{3,32}") || !tag.matches("[A-Za-z0-9]{2,8}")) throw new IllegalArgumentException("Nome ou tag inválidos.");
        if (state.clanByTag(tag).isPresent()) throw new IllegalArgumentException("Essa tag já existe.");
        Clan clan = new Clan(UUID.randomUUID(), name, tag, owner); repository.insertClan(clan); state.addClan(clan);
        discord.createClanResources(clan).whenComplete((resources, error) -> {
            if (error == null && resources.roleId() != null) { clan.setDiscord(resources.roleId(), resources.textId(), resources.voiceId()); repository.saveClanRuntime(clan); discord.syncClanIdentity(owner, resources.roleId(), ClanRole.LEADER); }
        });
        return clan;
    }
    public void invite(Clan clan, UUID actor, UUID player) {
        requireManager(clan, actor); if (state.clanOf(player).isPresent()) throw new IllegalArgumentException("Esse jogador já possui clã.");
        invites.put(player, new Invite(clan.id(), Instant.now().plusSeconds(300)));
    }
    public Clan accept(UUID player) {
        Invite invite = invites.remove(player); if (invite == null || invite.expiresAt().isBefore(Instant.now())) throw new IllegalArgumentException("Convite inexistente ou expirado.");
        Clan clan = state.clan(invite.clan()).orElseThrow(); clan.addMember(player, ClanRole.MEMBER); state.indexMember(player, clan.id()); repository.addMember(clan.id(), player, ClanRole.MEMBER);
        regions.syncClanMembers(clan.claims(), claimAccessors(clan)); memberChangeHook.accept(clan); discord.syncClanIdentity(player, clan.discordRoleId(), ClanRole.MEMBER); return clan;
    }
    public void leave(UUID player) {
        Clan clan = state.clanOf(player).orElseThrow(() -> new IllegalArgumentException("Você não possui clã."));
        if (clan.owner().equals(player)) throw new IllegalArgumentException("O líder deve dissolver ou transferir o clã.");
        clan.removeMember(player); state.unindexMember(player); repository.removeMember(player); regions.syncClanMembers(clan.claims(), claimAccessors(clan)); memberChangeHook.accept(clan); discord.removeClanIdentity(player, clan.discordRoleId());
    }
    public void promote(Clan clan, UUID actor, UUID player) {
        if (clan.roleOf(actor) != ClanRole.LEADER) throw new IllegalArgumentException("Apenas o líder pode promover.");
        ClanRole current = clan.roleOf(player); if (current == null || current == ClanRole.LEADER) throw new IllegalArgumentException("Membro inválido.");
        ClanRole next = current == ClanRole.MEMBER ? ClanRole.OFFICER : ClanRole.MEMBER; clan.setRole(player, next); repository.setRole(player, next); discord.syncClanIdentity(player, clan.discordRoleId(), next);
    }
    public void kick(Clan clan, UUID actor, UUID player) {
        requireManager(clan, actor); if (!clan.contains(player) || clan.owner().equals(player)) throw new IllegalArgumentException("Membro inválido.");
        clan.removeMember(player); state.unindexMember(player); repository.removeMember(player); regions.syncClanMembers(clan.claims(), claimAccessors(clan)); memberChangeHook.accept(clan); discord.removeClanIdentity(player, clan.discordRoleId());
    }
    public void setWar(Clan clan, UUID actor, boolean enabled) {
        throw new IllegalArgumentException("Todo clã é combatente por definição. Para ficar totalmente protegido, jogue sem clã e use seu santuário.");
    }
    public void tickWarStates() {
        Instant now = Instant.now(); for (Clan clan : state.clans()) {
            if (clan.warState() == WarState.ACTIVE && clan.warLockedUntil() == null) continue;
            clan.setWar(WarState.ACTIVE, now, null);
            repository.saveClanRuntime(clan);
        }
    }
    public int claimLimit(Clan clan) { return NemetonRules.clanClaimLimit(clan.members().size(), settings.claims().clanBase(), settings.claims().clanPerMember(), settings.claims().clanMaximum(), settings.claims().warBonusPercent(), true); }
    public void chat(Clan clan, String playerName, String message) {
        clan.members().keySet().stream().map(org.bukkit.Bukkit::getPlayer).filter(Objects::nonNull).forEach(p -> p.sendMessage("§7[§b" + clan.tag() + "§7] §f" + playerName + ": " + message));
        discord.clanMessage(clan, "**" + playerName + "**: " + message);
    }
    public void chatFromDiscord(Clan clan, String playerName, String message) {
        clan.members().keySet().stream().map(org.bukkit.Bukkit::getPlayer).filter(Objects::nonNull).forEach(p -> p.sendMessage("§7[§9Discord§7][§b" + clan.tag() + "§7] §f" + playerName + ": " + message));
    }
    public void requireManager(Clan clan, UUID player) { ClanRole role = clan.roleOf(player); if (role == null || !role.canManage()) throw new IllegalArgumentException("Apenas líder ou oficial pode fazer isso."); }
    public Set<UUID> claimAccessors(Clan clan) {
        Set<UUID> access = new HashSet<>(clan.members().keySet());
        access.addAll(state.clanTrustedPlayers(clan.id()));
        return access;
    }
    public void syncDiscordRoles() {
        for (Clan clan : state.clans()) clan.members().forEach((player, role) -> discord.syncClanIdentity(player, clan.discordRoleId(), role));
    }
    private record Invite(UUID clan, Instant expiresAt) {}
}
