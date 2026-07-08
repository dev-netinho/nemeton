package dev.nemeton.listener;

import dev.nemeton.config.Settings;
import dev.nemeton.service.ExperienceService;
import dev.nemeton.service.RaidService;
import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerListener implements Listener {
    private static final double LEGACY_HUB_X = 3136.5;
    private static final double LEGACY_HUB_Z = -6029.5;
    private static final double LEGACY_MIGRATION_RADIUS_SQUARED = 256.0 * 256.0;

    private final JavaPlugin plugin; private final Settings settings; private final RaidService raids; private final ExperienceService experience;
    public PlayerListener(JavaPlugin plugin, Settings settings, RaidService raids, ExperienceService experience) {
        this.plugin = plugin; this.settings = settings; this.raids = raids; this.experience = experience;
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        if (!event.getPlayer().hasPlayedBefore()) {
            experience.firstJoin(event.getPlayer());
            return;
        }
        Location hub = hubLocation();
        Location current = event.getPlayer().getLocation();
        if (hub != null && current.getWorld() != null && current.getWorld().equals(hub.getWorld())
                && horizontalDistanceSquared(current, LEGACY_HUB_X, LEGACY_HUB_Z) <= LEGACY_MIGRATION_RADIUS_SQUARED) {
            event.getPlayer().teleportAsync(hub).thenRun(() ->
                    Bukkit.getScheduler().runTask(plugin, () -> experience.welcome(event.getPlayer())));
            return;
        }
        experience.welcome(event.getPlayer());
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent event) {
        boolean raidRespawn = raids.respawnLock(event.getPlayer().getUniqueId()).isPresent();
        boolean personalRespawn = (event.isBedSpawn() || event.isAnchorSpawn()) && !event.isMissingRespawnBlock();
        Location hub = hubLocation();

        if (raidRespawn) {
            if (hub != null) event.setRespawnLocation(hub);
        } else if (event.getRespawnReason() == PlayerRespawnEvent.RespawnReason.DEATH && !personalRespawn && hub != null) {
            event.setRespawnLocation(hub);
        }

        raids.respawnLock(event.getPlayer().getUniqueId()).ifPresentOrElse(until -> {
            event.getPlayer().sendMessage("§eVocê retornará à raid após " + Math.max(1, java.time.Duration.between(java.time.Instant.now(), until).toSeconds()) + " segundos.");
            raids.returnAfterLock(event.getPlayer());
        }, () -> {
            if (event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH) return;
            if (personalRespawn) event.getPlayer().sendMessage("§aVocê renasceu no seu ponto de descanso.");
            else event.getPlayer().sendMessage("§6Você renasceu no §eNemeton§6.");
        });
    }

    private Location hubLocation() {
        World world = Bukkit.getWorld(settings.hub().world());
        if (world == null) return null;
        return new Location(world, settings.hub().x(), settings.hub().y(), settings.hub().z(), settings.hub().yaw(), settings.hub().pitch());
    }

    private double horizontalDistanceSquared(Location location, double x, double z) {
        double dx = location.getX() - x;
        double dz = location.getZ() - z;
        return dx * dx + dz * dz;
    }
}
