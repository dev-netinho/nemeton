package dev.nemeton.service;

import dev.nemeton.config.Settings;
import dev.nemeton.domain.*;
import dev.nemeton.integration.*;
import dev.nemeton.persistence.NemetonRepository;
import dev.nemeton.state.ServerState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RaidService {
    private final Plugin plugin; private final ServerState state; private final NemetonRepository repository;
    private final RegionGateway regions; private final DiscordBridge discord; private final Settings settings;
    private AllianceService alliances;
    private final Map<UUID, Set<String>> journaledBlocks = new ConcurrentHashMap<>();
    private final Set<UUID> captureArmed = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Instant> respawnLockedUntil = new ConcurrentHashMap<>();
    private final ExecutorService journalWriter = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("nemeton-raid-journal").daemon(true).unstarted(r));

    public RaidService(Plugin plugin, ServerState state, NemetonRepository repository, RegionGateway regions, DiscordBridge discord, Settings settings) {
        this.plugin = plugin; this.state = state; this.repository = repository; this.regions = regions; this.discord = discord; this.settings = settings;
    }
    public void setAllianceService(AllianceService alliances) { this.alliances = alliances; }

    public Raid declare(Clan attacker, Clan defender, UUID actor, int stake, List<Instant> slots) {
        if (attacker.roleOf(actor) != ClanRole.LEADER) throw new IllegalArgumentException("Apenas o líder pode declarar uma raid.");
        if (attacker.id().equals(defender.id())) throw new IllegalArgumentException("Um clã não pode atacar a si mesmo.");
        if (alliances != null && alliances.blocksRaid(attacker.id(), defender.id())) throw new IllegalArgumentException("Aliança ou trégua impede essa raid.");
        if (attacker.warState() != WarState.ACTIVE || defender.warState() != WarState.ACTIVE) throw new IllegalArgumentException("Os dois clãs precisam estar em modo de guerra ativo.");
        if (state.activeRaidForClan(attacker.id()).isPresent() || state.activeRaidForClan(defender.id()).isPresent()) throw new IllegalArgumentException("Um dos clãs já possui raid pendente.");
        if (defender.coffer() == null) throw new IllegalArgumentException("O defensor ainda não definiu seu cofre de guerra.");
        if (stake < settings.war().minimumStake() || stake > settings.war().maximumStake()) throw new IllegalArgumentException("A aposta deve ficar entre " + settings.war().minimumStake() + " e " + settings.war().maximumStake() + " diamantes.");
        if (attacker.cofferDiamonds() < stake || defender.cofferDiamonds() < stake) throw new IllegalArgumentException("Os dois cofres precisam cobrir a aposta.");
        Instant now = Instant.now(); NemetonRules.validateRaidSlots(slots, now, settings.war().declarationMinimum(), settings.war().declarationMaximum());
        Raid raid = new Raid(UUID.randomUUID(), attacker.id(), defender.id(), stake, slots, now.plus(settings.war().choiceWindow()));
        repository.reserveRaid(raid); attacker.withdraw(stake); defender.withdraw(stake); state.addRaid(raid);
        discord.alert("⚔️ **" + attacker.tag() + "** declarou uma raid contra **" + defender.tag() + "**. ID `" + shortId(raid.id()) + "`.");
        discord.clanMessage(defender, raidScheduleMessage(raid)); return raid;
    }

    public void schedule(Raid raid, Clan defender, UUID actor, int slot) {
        if (!raid.defenderId().equals(defender.id()) || defender.roleOf(actor) != ClanRole.LEADER) throw new IllegalArgumentException("Apenas o líder defensor escolhe o horário.");
        if (raid.state() != RaidState.DECLARED || raid.choiceDeadline().isBefore(Instant.now())) throw new IllegalArgumentException("A janela de escolha terminou.");
        raid.schedule(slot, raid.slots().get(slot - 1).plus(settings.war().duration())); repository.saveRaid(raid);
        discord.alert("🗓️ Raid `" + shortId(raid.id()) + "` agendada para <t:" + raid.startsAt().getEpochSecond() + ":F>.");
    }

    public void armCapture(UUID raidId, UUID player) {
        Raid raid = state.raid(raidId).orElseThrow(); if (raid.state() != RaidState.ACTIVE || raid.participants().get(player) != Raid.Side.ATTACKER) throw new IllegalArgumentException("Você não é atacante nessa raid.");
        captureArmed.add(raidId); discord.alert("🔥 O cofre da raid `" + shortId(raidId) + "` está sendo capturado!");
    }

    public void tick() {
        Instant now = Instant.now();
        for (Raid raid : state.raids()) {
            try {
                if (raid.state() == RaidState.DECLARED && !raid.choiceDeadline().isAfter(now)) scheduleDefault(raid);
                if (raid.state() == RaidState.SCHEDULED && !raid.startsAt().isAfter(now)) start(raid);
                if (raid.state() == RaidState.ACTIVE) tickActive(raid, now);
            } catch (Exception exception) { plugin.getLogger().severe("Falha no ciclo da raid " + raid.id() + ": " + exception.getMessage()); }
        }
    }

    private void scheduleDefault(Raid raid) { raid.schedule(1, raid.slots().getFirst().plus(settings.war().duration())); repository.saveRaid(raid); }

    private void start(Raid raid) {
        Clan attacker = state.clan(raid.attackerId()).orElseThrow(); Clan defender = state.clan(raid.defenderId()).orElseThrow();
        List<UUID> defenders = online(defender, settings.war().maximumTeam());
        List<UUID> attackers = online(attacker, Math.min(settings.war().maximumTeam(), defenders.size() + 1));
        if (attackers.size() < settings.war().minimumTeam()) { repository.cancelRaidWithPayouts(raid, 0, raid.stake() * 2); defender.deposit(raid.stake() * 2); raid.cancel(); discord.alert("Raid `" + shortId(raid.id()) + "` cancelada: atacantes ausentes; aposta entregue ao defensor."); return; }
        if (defenders.size() < settings.war().minimumTeam()) { int penalty = Math.max(1, raid.stake() / 4); repository.cancelRaidWithPayouts(raid, raid.stake() + penalty, raid.stake() - penalty); attacker.deposit(raid.stake() + penalty); defender.deposit(raid.stake() - penalty); raid.cancel(); discord.alert("Raid `" + shortId(raid.id()) + "` encerrada: defensores insuficientes."); return; }
        raid.start(attackers, defenders); repository.saveRaid(raid);
        List<UUID> all = new ArrayList<>(attackers); all.addAll(defenders); regions.createRaidOverlay(raid.id(), defender.claims(), all);
        teleportTeams(raid, attacker, defender, attackers, defenders); discord.alert("⚔️ Raid `" + shortId(raid.id()) + "` iniciada: " + attackers.size() + "×" + defenders.size() + ".");
    }

    private void tickActive(Raid raid, Instant now) {
        if (!raid.endsAt().isAfter(now)) { finish(raid, raid.defenderId(), "tempo esgotado"); return; }
        if (!captureArmed.contains(raid.id())) return;
        Clan defender = state.clan(raid.defenderId()).orElseThrow(); Clan.BlockPoint point = defender.coffer(); World world = Bukkit.getWorld(point.world()); if (world == null) return;
        Location center = new Location(world, point.x() + .5, point.y() + .5, point.z() + .5);
        boolean attackerNear = raid.participants().entrySet().stream().filter(e -> e.getValue() == Raid.Side.ATTACKER).map(e -> Bukkit.getPlayer(e.getKey())).filter(Objects::nonNull).anyMatch(p -> p.getWorld().equals(world) && p.getLocation().distanceSquared(center) <= 25);
        boolean defenderNear = raid.participants().entrySet().stream().filter(e -> e.getValue() == Raid.Side.DEFENDER).map(e -> Bukkit.getPlayer(e.getKey())).filter(Objects::nonNull).anyMatch(p -> p.getWorld().equals(world) && p.getLocation().distanceSquared(center) <= 64);
        raid.captureTick(attackerNear && !defenderNear); if (raid.captureSeconds() % 5 == 0) repository.saveRaid(raid);
        if (raid.captureSeconds() >= settings.war().captureSeconds()) finish(raid, raid.attackerId(), "cofre capturado");
    }

    public void recordOriginal(Raid raid, Block block, String blockData) {
        String key = block.getWorld().getName() + ':' + block.getX() + ':' + block.getY() + ':' + block.getZ();
        if (journaledBlocks.computeIfAbsent(raid.id(), ignored -> ConcurrentHashMap.newKeySet()).add(key)) journalWriter.execute(() -> repository.recordBlock(raid.id(), block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), blockData));
    }

    public void handleDeath(Player player, Raid raid) { respawnLockedUntil.put(player.getUniqueId(), Instant.now().plusSeconds(settings.war().deathLockSeconds())); }
    public Optional<Instant> respawnLock(UUID player) { return Optional.ofNullable(respawnLockedUntil.get(player)); }
    public void returnAfterLock(Player player) {
        Raid raid = state.raids().stream().filter(r -> r.state() == RaidState.ACTIVE && r.participants().containsKey(player.getUniqueId())).findFirst().orElse(null);
        if (raid == null) return;
        long seconds = Math.max(1, Duration.between(Instant.now(), respawnLockedUntil.getOrDefault(player.getUniqueId(), Instant.now())).toSeconds());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || raid.state() != RaidState.ACTIVE) return;
            Clan defender = state.clan(raid.defenderId()).orElse(null); if (defender == null || defender.coffer() == null) return;
            Clan.BlockPoint point = defender.coffer(); World world = Bukkit.getWorld(point.world()); if (world == null) return;
            Location location = raid.participants().get(player.getUniqueId()) == Raid.Side.DEFENDER
                    ? new Location(world, point.x() + .5, point.y() + 1, point.z() + .5)
                    : world.getHighestBlockAt(point.x() + 64, point.z()).getLocation().add(.5, 1, .5);
            player.teleportAsync(location); respawnLockedUntil.remove(player.getUniqueId());
        }, seconds * 20);
    }
    public boolean participant(Raid raid, UUID player) { return raid.participants().containsKey(player); }

    public void finish(Raid raid, UUID winnerId, String reason) {
        if (raid.state() != RaidState.ACTIVE) return; raid.beginRestore(); repository.saveRaid(raid); captureArmed.remove(raid.id());
        Clan defender = state.clan(raid.defenderId()).orElseThrow(); regions.removeRaidOverlay(raid.id(), defender.claims());
        restore(raid, () -> {
            Clan winner = state.clan(winnerId).orElseThrow(); repository.settleRaid(raid, winnerId, raid.stake() * 2); winner.deposit(raid.stake() * 2); raid.complete(winnerId);
            discord.alert("🏆 **" + winner.tag() + "** venceu a raid `" + shortId(raid.id()) + "` (" + reason + "). Território restaurado.");
        });
    }

    public void recoverOrphans() {
        for (Raid raid : state.raids()) {
            if (raid.state() == RaidState.ACTIVE || raid.state() == RaidState.RESTORING || raid.state() == RaidState.RECOVERY) {
                raid.recover(); repository.saveRaid(raid); restore(raid, () -> {
                    repository.cancelRaidWithPayouts(raid, raid.stake(), raid.stake());
                    state.clan(raid.attackerId()).ifPresent(c -> c.deposit(raid.stake()));
                    state.clan(raid.defenderId()).ifPresent(c -> { c.deposit(raid.stake()); regions.removeRaidOverlay(raid.id(), c.claims()); });
                    raid.cancel(); discord.alert("🛟 Raid `" + shortId(raid.id()) + "` recuperada após reinício; apostas devolvidas.");
                });
            }
        }
    }

    private void restore(Raid raid, Runnable completed) {
        journalWriter.execute(() -> {
            List<NemetonRepository.BlockChange> changes = repository.blockChanges(raid.id()); Collections.reverse(changes); Iterator<NemetonRepository.BlockChange> iterator = changes.iterator();
            Bukkit.getScheduler().runTask(plugin, () -> {
                final BukkitTask[] task = new BukkitTask[1]; task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    int budget = 500; while (budget-- > 0 && iterator.hasNext()) { var change = iterator.next(); World world = Bukkit.getWorld(change.world()); if (world != null) world.getBlockAt(change.x(), change.y(), change.z()).setBlockData(Bukkit.createBlockData(change.blockData()), false); }
                    if (!iterator.hasNext()) { task[0].cancel(); journaledBlocks.remove(raid.id()); completed.run(); }
                }, 1L, 1L);
            });
        });
    }

    private List<UUID> online(Clan clan, int limit) { return clan.members().keySet().stream().filter(id -> Bukkit.getPlayer(id) != null).limit(limit).toList(); }
    private void teleportTeams(Raid raid, Clan attacker, Clan defender, List<UUID> attackers, List<UUID> defenders) {
        Clan.BlockPoint point = defender.coffer(); World world = Bukkit.getWorld(point.world()); if (world == null) return;
        Location defense = new Location(world, point.x() + .5, point.y() + 1, point.z() + .5); Location attack = world.getHighestBlockAt(point.x() + 64, point.z()).getLocation().add(.5, 1, .5);
        defenders.forEach(id -> Bukkit.getPlayer(id).teleportAsync(defense)); attackers.forEach(id -> Bukkit.getPlayer(id).teleportAsync(attack));
    }
    private String raidScheduleMessage(Raid raid) { return "⚔️ Raid `" + shortId(raid.id()) + "`. Escolha com `/raid agendar " + shortId(raid.id()) + " <1|2|3>`: " + raid.slots().stream().map(i -> "<t:" + i.getEpochSecond() + ":F>").toList(); }
    public static String shortId(UUID id) { return id.toString().substring(0, 8); }
    public Optional<Raid> byShortId(String shortId) { return state.raids().stream().filter(r -> r.id().toString().startsWith(shortId)).findFirst(); }
    public void shutdown() {
        journalWriter.shutdown();
        try { journalWriter.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
