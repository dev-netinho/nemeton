package dev.nemeton.service;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

public final class GraveService implements Listener, TabExecutor {
    private static final int BARREL_SIZE = 27;

    private final JavaPlugin plugin;
    private final NamespacedKey graveIdKey;
    private final NamespacedKey ownerKey;
    private final File file;
    private final Map<UUID, Grave> lastGraves = new HashMap<>();

    public GraveService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.graveIdKey = new NamespacedKey(plugin, "grave_id");
        this.ownerKey = new NamespacedKey(plugin, "grave_owner");
        this.file = new File(plugin.getDataFolder(), "graves.yml");
        load();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        event.deathMessage(null);
        event.setDroppedExp(0);
        event.setKeepLevel(true);

        List<ItemStack> drops = event.getDrops().stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getType() != Material.AIR)
                .map(ItemStack::clone)
                .toList();
        event.getDrops().clear();
        if (drops.isEmpty()) return;

        Grave grave = createGrave(player, drops, player.getLocation());
        lastGraves.put(player.getUniqueId(), grave);
        save();
        player.sendMessage("§8Sua morte foi registrada de forma privada. §7Use §f/lapide§7 para ver a última localização.");
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Grave grave = lastGraves.get(player.getUniqueId());
            if (grave == null) return;
            Location location = grave.location();
            if (location.getWorld() != null && location.getWorld().equals(player.getWorld())) {
                player.setCompassTarget(location);
            }
            giveRecoveryCompass(player);
            player.sendActionBar(Component.text("Lápide em " + format(location) + " — /lapide"));
        }, 20L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || !isGrave(block)) return;
        UUID owner = owner(block);
        if (!canAccess(event.getPlayer(), owner)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cEssa lápide pertence a outro jogador.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isGrave(event.getBlock())) return;
        event.setCancelled(true);
        UUID owner = owner(event.getBlock());
        event.getPlayer().sendMessage(canAccess(event.getPlayer(), owner)
                ? "§eAbra a lápide e retire os itens; ela some quando esvaziar."
                : "§cEssa lápide pertence a outro jogador.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isGrave);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        if (!(inventory.getHolder() instanceof Barrel barrel)) return;
        Block block = barrel.getBlock();
        if (!isGrave(block) || !isEmpty(inventory)) return;
        UUID owner = owner(block);
        UUID graveId = graveId(block);
        Location location = block.getLocation();
        block.setType(Material.AIR, false);
        if (!hasAdjacentPart(location, graveId)) removeIfThisWasLast(owner, location);
        if (event.getPlayer() instanceof Player player) {
            player.sendMessage("§aLápide esvaziada.");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Comando disponível apenas em jogo.");
            return true;
        }
        Grave grave = lastGraves.get(player.getUniqueId());
        if (grave == null) {
            player.sendMessage("§eVocê ainda não tem lápide registrada.");
            return true;
        }
        Location location = grave.location();
        if (location.getWorld() == null) {
            player.sendMessage("§cO mundo da sua lápide não está carregado.");
            return true;
        }
        if (location.getWorld().equals(player.getWorld())) {
            player.setCompassTarget(location);
            giveRecoveryCompass(player);
            player.sendMessage("§6Última lápide: §f" + format(location) + "§7. A bússola foi apontada para ela.");
        } else {
            player.sendMessage("§6Última lápide: §f" + location.getWorld().getName() + " " + format(location) + "§7.");
            player.sendMessage("§7A bússola só aponta quando você está na mesma dimensão.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    private Grave createGrave(Player player, List<ItemStack> drops, Location death) {
        int barrels = drops.size() > BARREL_SIZE ? 2 : 1;
        Block base = findBase(death, barrels);
        UUID graveId = UUID.randomUUID();
        List<ItemStack> overflow = new ArrayList<>();
        int index = 0;

        for (int barrelIndex = 0; barrelIndex < barrels; barrelIndex++) {
            Block block = base.getRelative(BlockFace.UP, barrelIndex);
            block.setType(Material.BARREL, false);
            Barrel barrel = (Barrel) block.getState();
            barrel.customName(Component.text("Lápide de " + player.getName()));
            barrel.getPersistentDataContainer().set(graveIdKey, PersistentDataType.STRING, graveId.toString());
            barrel.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
            barrel.update(true, false);

            Inventory inventory = ((Barrel) block.getState()).getInventory();
            for (int slot = 0; slot < inventory.getSize() && index < drops.size(); slot++, index++) {
                inventory.setItem(slot, drops.get(index));
            }
        }
        while (index < drops.size()) overflow.add(drops.get(index++));
        overflow.forEach(item -> death.getWorld().dropItemNaturally(death, item));

        Location location = base.getLocation();
        player.sendMessage("§7Sua lápide ficou em §f" + format(location) + "§7. Ela guarda seus itens sem anunciar para todo mundo.");
        return new Grave(graveId, player.getUniqueId(), death.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), Instant.now().toEpochMilli());
    }

    private Block findBase(Location death, int barrels) {
        World world = Objects.requireNonNull(death.getWorld());
        Block origin = death.getBlock();
        for (int radius = 0; radius <= 4; radius++) {
            for (int dy = 0; dy <= 4; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        Block candidate = world.getBlockAt(origin.getX() + dx, clampY(world, origin.getY() + dy), origin.getZ() + dz);
                        if (canUseColumn(candidate, barrels)) return candidate;
                    }
                }
            }
        }
        Block highest = world.getHighestBlockAt(death).getRelative(BlockFace.UP);
        return canUseColumn(highest, barrels) ? highest : origin;
    }

    private int clampY(World world, int y) {
        return Math.max(world.getMinHeight() + 1, Math.min(world.getMaxHeight() - 3, y));
    }

    private boolean canUseColumn(Block base, int barrels) {
        for (int i = 0; i < barrels; i++) {
            if (!canReplace(base.getRelative(BlockFace.UP, i))) return false;
        }
        return true;
    }

    private boolean canReplace(Block block) {
        Material type = block.getType();
        return type.isAir() || (block.isPassable() && type != Material.WATER && type != Material.LAVA);
    }

    private boolean isGrave(Block block) {
        if (block.getType() != Material.BARREL) return false;
        if (!(block.getState() instanceof TileState state)) return false;
        return state.getPersistentDataContainer().has(graveIdKey, PersistentDataType.STRING);
    }

    private UUID owner(Block block) {
        if (!(block.getState() instanceof TileState state)) return null;
        String value = state.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (value == null) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; }
    }

    private UUID graveId(Block block) {
        if (!(block.getState() instanceof TileState state)) return null;
        String value = state.getPersistentDataContainer().get(graveIdKey, PersistentDataType.STRING);
        if (value == null) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; }
    }

    private boolean hasAdjacentPart(Location location, UUID graveId) {
        if (graveId == null || location.getWorld() == null) return false;
        for (int dy : new int[]{-1, 1}) {
            Block adjacent = location.getWorld().getBlockAt(
                    location.getBlockX(), location.getBlockY() + dy, location.getBlockZ());
            if (graveId.equals(graveId(adjacent))) return true;
        }
        return false;
    }

    private boolean canAccess(Player player, UUID owner) {
        return owner != null && (player.getUniqueId().equals(owner) || player.hasPermission("nemeton.admin"));
    }

    private boolean isEmpty(Inventory inventory) {
        return Arrays.stream(inventory.getContents()).allMatch(item -> item == null || item.getType() == Material.AIR);
    }

    private void removeIfThisWasLast(UUID owner, Location location) {
        if (owner == null) return;
        Grave grave = lastGraves.get(owner);
        if (grave == null) return;
        if (grave.world().equals(location.getWorld().getName()) && grave.x() == location.getBlockX() && grave.y() == location.getBlockY() && grave.z() == location.getBlockZ()) {
            lastGraves.remove(owner);
            save();
        }
    }

    private void giveRecoveryCompass(Player player) {
        ItemStack compass = new ItemStack(Material.RECOVERY_COMPASS);
        ItemMeta meta = compass.getItemMeta();
        meta.displayName(Component.text("Bússola da Lápide"));
        meta.lore(List.of(Component.text("/lapide mostra as coordenadas da última morte.")));
        compass.setItemMeta(meta);
        player.getInventory().addItem(compass).values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private String format(Location location) {
        return location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("graves");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                UUID owner = UUID.fromString(key);
                UUID id = UUID.fromString(section.getString(key + ".id", UUID.randomUUID().toString()));
                lastGraves.put(owner, new Grave(id, owner, section.getString(key + ".world", "world"),
                        section.getInt(key + ".x"), section.getInt(key + ".y"), section.getInt(key + ".z"),
                        section.getLong(key + ".created")));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void save() {
        YamlConfiguration config = new YamlConfiguration();
        for (Grave grave : lastGraves.values()) {
            String path = "graves." + grave.owner();
            config.set(path + ".id", grave.id().toString());
            config.set(path + ".world", grave.world());
            config.set(path + ".x", grave.x());
            config.set(path + ".y", grave.y());
            config.set(path + ".z", grave.z());
            config.set(path + ".created", grave.created());
        }
        try {
            config.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Não foi possível salvar lápides: " + exception.getMessage());
        }
    }

    private record Grave(UUID id, UUID owner, String world, int x, int y, int z, long created) {
        Location location() {
            World loaded = Bukkit.getWorld(world);
            return loaded == null ? new Location(Bukkit.getWorlds().getFirst(), x, y, z) : new Location(loaded, x, y, z);
        }
    }
}
