package dev.nemeton.service;

import dev.nemeton.config.Settings;
import dev.nemeton.domain.ChunkPos;
import dev.nemeton.state.ServerState;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class TeleportService {
    private final Plugin plugin; private final Settings settings; private final ServerState state;
    private final Map<UUID, Instant> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> combatUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    public TeleportService(Plugin plugin, Settings settings, ServerState state) { this.plugin = plugin; this.settings = settings; this.state = state; }

    public void request(Player player) {
        Instant now = Instant.now(); if (cooldowns.getOrDefault(player.getUniqueId(), Instant.EPOCH).isAfter(now)) throw new IllegalArgumentException("O Nemeton ainda está em cooldown.");
        if (combatUntil.getOrDefault(player.getUniqueId(), Instant.EPOCH).isAfter(now)) throw new IllegalArgumentException("Você está em combate.");
        if (state.activeRaidAt(ChunkPos.of(player.getChunk())).filter(r -> r.participants().containsKey(player.getUniqueId())).isPresent()) throw new IllegalArgumentException("Não é possível sair de uma raid.");
        if (pending.containsKey(player.getUniqueId())) throw new IllegalArgumentException("Teleporte já sendo preparado.");
        Location origin = player.getLocation().clone(); long ticks = settings.hub().warmup().toSeconds() * 20;
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(player.getUniqueId()); World world = Bukkit.getWorld(settings.hub().world()); if (world == null || !player.isOnline()) return;
            player.teleportAsync(new Location(world, settings.hub().x(), settings.hub().y(), settings.hub().z(), settings.hub().yaw(), settings.hub().pitch())); cooldowns.put(player.getUniqueId(), Instant.now().plus(settings.hub().cooldown()));
        }, ticks);
        pending.put(player.getUniqueId(), new Pending(origin, task)); player.sendMessage("§eRetorno ao Nemeton em " + settings.hub().warmup().toSeconds() + " segundos. Não se mova.");
    }
    public void moved(Player player) {
        Pending value = pending.get(player.getUniqueId()); if (value == null || value.origin().getWorld() != player.getWorld()) return;
        if (value.origin().distanceSquared(player.getLocation()) > .25) { value.task().cancel(); pending.remove(player.getUniqueId()); player.sendMessage("§cTeleporte cancelado: você se moveu."); }
    }
    public void tagCombat(UUID player) { combatUntil.put(player, Instant.now().plusSeconds(30)); }
    private record Pending(Location origin, BukkitTask task) {}
}

