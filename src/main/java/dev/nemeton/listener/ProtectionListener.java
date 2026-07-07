package dev.nemeton.listener;

import dev.nemeton.domain.*;
import dev.nemeton.integration.DiscordBridge;
import dev.nemeton.service.*;
import dev.nemeton.state.ServerState;
import org.bukkit.block.TileState;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ProtectionListener implements Listener {
    private final ServerState state; private final ClaimService claims; private final RaidService raids; private final DiscordBridge discord;
    private final Map<String, Instant> intrusionCooldown = new ConcurrentHashMap<>();
    public ProtectionListener(ServerState state, ClaimService claims, RaidService raids, DiscordBridge discord) { this.state = state; this.claims = claims; this.raids = raids; this.discord = discord; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        ChunkPos chunk = ChunkPos.of(event.getBlock().getChunk()); Optional<Raid> raid = state.activeRaidAt(chunk);
        if (claims.isHub(event.getBlock().getLocation())) { event.setCancelled(true); deny(event.getPlayer()); return; }
        if (!claims.canAccess(event.getPlayer().getUniqueId(), chunk)) { event.setCancelled(true); deny(event.getPlayer()); return; }
        if (raid.isPresent()) {
            if (event.getBlock().getState() instanceof TileState) { event.setCancelled(true); event.getPlayer().sendMessage("§cContêineres e blocos especiais são protegidos durante raids."); return; }
            raids.recordOriginal(raid.get(), event.getBlock(), event.getBlock().getBlockData().getAsString()); event.setDropItems(false); event.setExpToDrop(0);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (claims.isHub(event.getBlock().getLocation())) { event.setCancelled(true); deny(event.getPlayer()); return; }
        ChunkPos chunk = ChunkPos.of(event.getBlock().getChunk()); if (!claims.canAccess(event.getPlayer().getUniqueId(), chunk)) { event.setCancelled(true); deny(event.getPlayer()); return; }
        state.activeRaidAt(chunk).ifPresent(raid -> raids.recordOriginal(raid, event.getBlock(), event.getBlockReplacedState().getBlockData().getAsString()));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return; ChunkPos chunk = ChunkPos.of(event.getClickedBlock().getChunk());
        if (!claims.canAccess(event.getPlayer().getUniqueId(), chunk)) { event.setCancelled(true); deny(event.getPlayer()); return; }
        Optional<Raid> currentRaid = state.activeRaidAt(chunk);
        if (currentRaid.isPresent() && event.getClickedBlock().getState() instanceof TileState
                && currentRaid.get().participants().get(event.getPlayer().getUniqueId()) == Raid.Side.ATTACKER) {
            event.setCancelled(true); event.getPlayer().sendMessage("§cOs contêineres do defensor não podem ser saqueados."); return;
        }
        state.activeRaidAt(chunk).ifPresent(raid -> state.clan(raid.defenderId()).ifPresent(defender -> {
            Clan.BlockPoint point = defender.coffer(); if (point != null && point.world().equals(event.getClickedBlock().getWorld().getName()) && point.x() == event.getClickedBlock().getX() && point.y() == event.getClickedBlock().getY() && point.z() == event.getClickedBlock().getZ()) {
                event.setCancelled(true); try { raids.armCapture(raid.id(), event.getPlayer().getUniqueId()); event.getPlayer().sendMessage("§6Captura iniciada. Mantenha a área livre de defensores por três minutos."); } catch (IllegalArgumentException e) { event.getPlayer().sendMessage("§c" + e.getMessage()); }
            }
        }));
    }
    @EventHandler public void onFluid(BlockFromToEvent event) { if (state.activeRaidAt(ChunkPos.of(event.getToBlock().getChunk())).isPresent()) event.setCancelled(true); }
    @EventHandler public void onSpread(BlockSpreadEvent event) { if (state.activeRaidAt(ChunkPos.of(event.getBlock().getChunk())).isPresent()) event.setCancelled(true); }
    @EventHandler public void onPiston(BlockPistonExtendEvent event) { if (state.activeRaidAt(ChunkPos.of(event.getBlock().getChunk())).isPresent()) event.setCancelled(true); }
    @EventHandler public void onPiston(BlockPistonRetractEvent event) { if (state.activeRaidAt(ChunkPos.of(event.getBlock().getChunk())).isPresent()) event.setCancelled(true); }
    @EventHandler public void onBucket(PlayerBucketEmptyEvent event) { if (claims.isHub(event.getBlock().getLocation()) || state.activeRaidAt(ChunkPos.of(event.getBlock().getChunk())).isPresent()) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> {
            if (claims.isHub(block.getLocation())) return true;
            ChunkPos chunk = ChunkPos.of(block.getChunk()); Optional<Raid> raid = state.activeRaidAt(chunk);
            if (raid.isEmpty() || block.getState() instanceof TileState || state.sanctuaryOwner(chunk).isPresent()) return true;
            raids.recordOriginal(raid.get(), block, block.getBlockData().getAsString()); return false;
        }); event.setYield(0);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && claims.isHub(player.getLocation())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player victim && event.getDamager() instanceof Player attacker) {
            ChunkPos chunk = ChunkPos.of(victim.getChunk()); Optional<Raid> raid = state.activeRaidAt(chunk);
            if (raid.isPresent() && (!raids.participant(raid.get(), victim.getUniqueId()) || !raids.participant(raid.get(), attacker.getUniqueId()))) event.setCancelled(true);
        } else if (!(event.getEntity() instanceof Monster)) event.setCancelled(state.activeRaidAt(ChunkPos.of(event.getEntity().getChunk())).isPresent());
    }
    @EventHandler public void onWitherSpawn(EntitySpawnEvent event) { if (event.getEntity() instanceof Wither && (claims.isHub(event.getLocation()) || state.clanAt(ChunkPos.of(event.getLocation().getChunk())).isPresent() || state.sanctuaryOwner(ChunkPos.of(event.getLocation().getChunk())).isPresent())) event.setCancelled(true); }
    @EventHandler public void onBossDeath(EntityDeathEvent event) {
        String boss = event.getEntity() instanceof EnderDragon ? "Dragão do End" : event.getEntity() instanceof Wither ? "Wither" : null;
        if (boss == null) return;
        String message = "🏆 O **" + boss + "** foi derrotado pela comunidade!"; discord.alert(message);
        org.bukkit.Bukkit.broadcast(net.kyori.adventure.text.Component.text("O " + boss + " foi derrotado pela comunidade!"));
    }
    @EventHandler public void onDeath(PlayerDeathEvent event) {
        state.activeRaidAt(ChunkPos.of(event.getPlayer().getChunk())).filter(r -> raids.participant(r, event.getPlayer().getUniqueId())).ifPresent(raid -> {
            event.setKeepInventory(true); event.getDrops().clear(); event.setKeepLevel(true); event.setDroppedExp(0); raids.handleDeath(event.getPlayer(), raid);
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && claims.isHub(player.getLocation())) {
            event.setCancelled(true);
            player.setFoodLevel(Math.max(player.getFoodLevel(), 18));
        }
    }
    @EventHandler public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getFrom().getChunk().equals(event.getTo().getChunk())) return;
        ChunkPos to = ChunkPos.of(event.getTo().getChunk()); state.clanAt(to).ifPresent(clan -> {
            if (clan.contains(event.getPlayer().getUniqueId())) return; String key = clan.id() + ":" + event.getPlayer().getUniqueId(); Instant now = Instant.now();
            if (intrusionCooldown.getOrDefault(key, Instant.EPOCH).isAfter(now)) return; intrusionCooldown.put(key, now.plusSeconds(600));
            discord.clanMessage(clan, "⚠️ **" + event.getPlayer().getName() + "** entrou no território em `" + to.x() + ", " + to.z() + "`.");
        });
    }
    private void deny(Player player) { player.sendActionBar(net.kyori.adventure.text.Component.text("Este território está protegido.")); }
}
