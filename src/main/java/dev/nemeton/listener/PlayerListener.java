package dev.nemeton.listener;

import dev.nemeton.config.Settings;
import dev.nemeton.service.RaidService;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import net.kyori.adventure.text.Component;

public final class PlayerListener implements Listener {
    private final Settings settings; private final RaidService raids;
    public PlayerListener(Settings settings, RaidService raids) { this.settings = settings; this.raids = raids; }

    @EventHandler public void onFirstJoin(PlayerJoinEvent event) {
        if (event.getPlayer().hasPlayedBefore()) return; Player player = event.getPlayer(); World world = Bukkit.getWorld(settings.hub().world());
        if (world != null) player.teleportAsync(new Location(world, settings.hub().x(), settings.hub().y(), settings.hub().z(), settings.hub().yaw(), settings.hub().pitch()));
        ItemStack guide = new ItemStack(Material.WRITTEN_BOOK); BookMeta meta = (BookMeta) guide.getItemMeta(); meta.title(Component.text("Guia do Nemeton")); meta.author(Component.text("A comunidade"));
        meta.addPages(Component.text("Bem-vindo ao Nemeton.\n\n/clan ajuda\n/santuario ajuda\n/raid ajuda\n/nemeton\n\nO mundo é persistente. Respeite as construções e negocie na praça.")); guide.setItemMeta(meta); player.getInventory().addItem(guide);
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent event) {
        raids.respawnLock(event.getPlayer().getUniqueId()).ifPresent(until -> {
            World world = Bukkit.getWorld(settings.hub().world());
            if (world != null) event.setRespawnLocation(new Location(world, settings.hub().x(), settings.hub().y(), settings.hub().z()));
            event.getPlayer().sendMessage("§eVocê retornará à raid após " + Math.max(1, java.time.Duration.between(java.time.Instant.now(), until).toSeconds()) + " segundos.");
            raids.returnAfterLock(event.getPlayer());
        });
    }
}
