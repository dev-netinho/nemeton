package dev.nemeton.service;

import dev.nemeton.config.Settings;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/** A native cross-play map plus a link to the optional live browser map. */
public final class MapService implements TabExecutor {
    private final JavaPlugin plugin;
    private final Settings settings;
    private final NamespacedKey mapItemKey;
    private MapView nemetonMap;

    public MapService(JavaPlugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.mapItemKey = new NamespacedKey(plugin, "nemeton_map");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Comando disponível apenas em jogo.");
            return true;
        }
        player.setCompassTarget(new Location(player.getWorld(), settings.hub().centerX(), settings.hub().y(), settings.hub().centerZ()));
        if (!hasMap(player)) {
            ItemStack map = createMapItem(player.getWorld());
            player.getInventory().addItem(map).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            player.sendMessage("§aMapa do Nemeton entregue. Segure-o para revelar o terreno ao redor da clareira.");
        } else {
            player.sendMessage("§eSeu Mapa do Nemeton já está no inventário.");
        }
        String publicUrl = liveMapUrl();
        if (publicUrl != null && !publicUrl.isBlank()) {
            player.sendMessage("§6Mapa ao vivo: §f" + centeredLiveMapUrl(publicUrl));
        }
        player.sendMessage("§7Lápides ficam privadas: §f/lapide§7 mostra as coordenadas e aponta a bússola sem poluir o mapa de todos.");
        return true;
    }

    private String liveMapUrl() {
        File file = new File(plugin.getDataFolder(), "map-url.txt");
        if (file.isFile()) {
            try { return Files.readString(file.toPath()).trim(); }
            catch (IOException exception) { plugin.getLogger().warning("Não foi possível ler map-url.txt: " + exception.getMessage()); }
        }
        return plugin.getConfig().getString("map.public-url", "");
    }

    private String centeredLiveMapUrl(String baseUrl) {
        String url = baseUrl.strip();
        if (url.contains("world=") || url.contains("uuid=")) return url;
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "world=minecraft_overworld&zoom=3&x="
                + Math.round(settings.hub().centerX()) + "&z=" + Math.round(settings.hub().centerZ());
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { return List.of(); }

    private boolean hasMap(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() != Material.FILLED_MAP) continue;
            if (item.getPersistentDataContainer().has(mapItemKey, PersistentDataType.BYTE)) return true;
        }
        return false;
    }

    private ItemStack createMapItem(World world) {
        MapView view = mapView(world);
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        meta.displayName(Component.text("Mapa do Nemeton"));
        meta.lore(List.of(
                Component.text("Mapa nativo Java + Bedrock."),
                Component.text("O centro dourado é a clareira segura.")));
        item.setItemMeta(meta);
        item.editPersistentDataContainer(container -> container.set(mapItemKey, PersistentDataType.BYTE, (byte) 1));
        return item;
    }

    private MapView mapView(World world) {
        if (nemetonMap != null && nemetonMap.getWorld() != null) return nemetonMap;
        File stateFile = new File(plugin.getDataFolder(), "map.yml");
        YamlConfiguration state = YamlConfiguration.loadConfiguration(stateFile);
        int id = state.getInt("nemeton-map-id", -1);
        if (id >= 0) nemetonMap = Bukkit.getMap(id);
        if (nemetonMap == null || nemetonMap.getWorld() == null || !nemetonMap.getWorld().equals(world)) {
            nemetonMap = Bukkit.createMap(world);
            state.set("nemeton-map-id", nemetonMap.getId());
            try { state.save(stateFile); }
            catch (IOException exception) { plugin.getLogger().warning("Não foi possível salvar map.yml: " + exception.getMessage()); }
        }
        nemetonMap.setCenterX((int) settings.hub().centerX());
        nemetonMap.setCenterZ((int) settings.hub().centerZ());
        nemetonMap.setScale(MapView.Scale.NORMAL);
        nemetonMap.setTrackingPosition(true);
        nemetonMap.setUnlimitedTracking(true);
        nemetonMap.setLocked(false);
        return nemetonMap;
    }
}
