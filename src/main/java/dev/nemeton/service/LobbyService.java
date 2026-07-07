package dev.nemeton.service;

import dev.nemeton.config.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/** Owns the visible and interactive experience of the Nemeton clearing. */
public final class LobbyService implements Listener, CommandExecutor {
    private static final int CHANGES_PER_TICK = 450;

    private final JavaPlugin plugin;
    private final Settings settings;
    private final NamespacedKey entityKey;
    private final Map<UUID, Boolean> safeState = new HashMap<>();
    private boolean building;

    public LobbyService(JavaPlugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.entityKey = new NamespacedKey(plugin, "lobby_entity");
        Bukkit.getScheduler().runTaskLater(plugin, this::spawnLobbyEntities, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::faceNearbyPlayers, 40L, 20L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> safeState.put(event.getPlayer().getUniqueId(), isInside(event.getPlayer().getLocation())), 10L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ())) return;
        Player player = event.getPlayer();
        boolean nowSafe = isInside(event.getTo());
        Boolean previous = safeState.put(player.getUniqueId(), nowSafe);
        if (previous == null || previous == nowSafe) return;
        if (nowSafe) {
            player.sendActionBar(Component.text("✶ Nemeton — zona segura: sem PvP e sem grife", NamedTextColor.GREEN));
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.2f);
        } else {
            player.sendActionBar(Component.text("⚔ Terras selvagens — proteja sua base com /santuario", NamedTextColor.GOLD));
            player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.35f, 1.4f);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        String role = event.getRightClicked().getPersistentDataContainer().get(entityKey, PersistentDataType.STRING);
        if (role == null || !role.startsWith("npc:")) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.8f, 1.1f);
        switch (role.substring(4)) {
            case "guide" -> {
                player.sendMessage("§a§lEira, Guia do Nemeton");
                player.sendMessage("§7Aqui é uma zona segura. Leia §f/guia§7, pegue §f/kit§7 e use §f/mapa§7 para se orientar.");
            }
            case "clans" -> {
                player.sendMessage("§c§lBorin, Mestre dos Clãs");
                player.sendMessage("§7Crie uma equipe com §f/clan criar <nome> <tag>§7. Fora daqui, §f/clan claim§7 protege o território conectado.");
            }
            case "trade" -> {
                player.sendMessage("§6§lMara, Mercadora");
                player.sendMessage("§7Negocie sem loja infinita: §f/troca <jogador>§7. A janela pode ser fechada e retomada com §f/troca abrir§7.");
            }
            case "wilds" -> {
                player.sendMessage("§b§lTarin, Batedor");
                player.sendMessage("§7Além dos portais começa o survival. Faça uma mochila, marque seu santuário e use §f/lapide§7 após uma morte.");
            }
            default -> { }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player && !player.hasPermission("nemeton.admin")) {
            sender.sendMessage("§cSem permissão.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("Nemeton: centro " + blockCenterX() + ", " + blockCenterZ()
                    + "; raio " + settings.hub().radius() + "; construção " + (building ? "em andamento" : "parada") + ".");
            return true;
        }
        if (args[0].equalsIgnoreCase("npcs")) {
            spawnLobbyEntities();
            sender.sendMessage("§aNPCs e sinalização recriados.");
            return true;
        }
        if (args[0].equalsIgnoreCase("construir")) {
            buildLobby(sender);
            return true;
        }
        sender.sendMessage("Use /nemetonadmin construir|npcs|status");
        return true;
    }

    public boolean isInside(Location location) {
        if (location.getWorld() == null || !location.getWorld().getName().equals(settings.hub().world())) return false;
        double dx = location.getX() - settings.hub().centerX();
        double dz = location.getZ() - settings.hub().centerZ();
        return dx * dx + dz * dz <= settings.hub().radius() * settings.hub().radius();
    }

    private void buildLobby(CommandSender sender) {
        if (building) {
            sender.sendMessage("§eA construção já está em andamento.");
            return;
        }
        World world = world();
        if (world == null) {
            sender.sendMessage("§cMundo do Nemeton não carregado.");
            return;
        }
        building = true;
        List<Runnable> changes = new ArrayList<>();
        planPaths(world, changes);
        planBoundary(world, changes);
        planGateways(world, changes);
        planLanternsAndGardens(world, changes);
        sender.sendMessage("§6Reforma do Nemeton iniciada: " + changes.size() + " alterações em lotes seguros.");

        Iterator<Runnable> iterator = changes.iterator();
        new BukkitRunnable() {
            @Override public void run() {
                int count = 0;
                while (iterator.hasNext() && count++ < CHANGES_PER_TICK) iterator.next().run();
                if (iterator.hasNext()) return;
                cancel();
                building = false;
                spawnLobbyEntities();
                world.save();
                sender.sendMessage("§aReforma concluída. Caminhos, limite seguro, portais, luzes e NPCs estão prontos.");
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void planPaths(World world, List<Runnable> changes) {
        int cx = blockCenterX(), cz = blockCenterZ();
        for (int x = cx - 66; x <= cx + 66; x++) {
            for (int z = cz - 66; z <= cz + 66; z++) {
                int dx = x - cx, dz = z - cz;
                double distance = Math.hypot(dx, dz);
                boolean circularWalk = distance >= 21.5 && distance <= 25.5;
                boolean northSouth = Math.abs(dx) <= 2 && distance >= 23 && Math.abs(dz) <= 63;
                boolean eastWest = Math.abs(dz) <= 2 && distance >= 23 && Math.abs(dx) <= 63;
                if (!circularWalk && !northSouth && !eastWest) continue;
                int y = naturalSurfaceY(world, x, z);
                Material material = Math.floorMod(x * 31 + z * 17, 9) == 0 ? Material.MOSSY_COBBLESTONE
                        : Math.floorMod(x * 13 + z * 7, 5) == 0 ? Material.MUD_BRICKS : Material.PACKED_MUD;
                int blockX = x, blockZ = z;
                changes.add(() -> set(world, blockX, y, blockZ, material));
                clearPlants(world, changes, x, y + 1, z);
            }
        }
    }

    private void planBoundary(World world, List<Runnable> changes) {
        int cx = blockCenterX(), cz = blockCenterZ();
        Set<Long> planned = new HashSet<>();
        for (int degree = 0; degree < 360; degree++) {
            double angle = Math.toRadians(degree);
            int dx = (int) Math.round(Math.cos(angle) * 63);
            int dz = (int) Math.round(Math.sin(angle) * 63);
            if (Math.abs(dx) <= 5 || Math.abs(dz) <= 5) continue;
            int x = cx + dx, z = cz + dz;
            long key = (((long) x) << 32) ^ (z & 0xffffffffL);
            if (!planned.add(key)) continue;
            int y = naturalSurfaceY(world, x, z);
            Material wall = Math.floorMod(x + z, 7) == 0 ? Material.COBBLESTONE_WALL : Material.MOSSY_COBBLESTONE_WALL;
            changes.add(() -> set(world, x, y + 1, z, wall));
        }
    }

    private void planGateways(World world, List<Runnable> changes) {
        planGateway(world, changes, 0, -63, true);
        planGateway(world, changes, 0, 63, true);
        planGateway(world, changes, -63, 0, false);
        planGateway(world, changes, 63, 0, false);
    }

    private void planGateway(World world, List<Runnable> changes, int offsetX, int offsetZ, boolean alongX) {
        int cx = blockCenterX(), cz = blockCenterZ();
        int[] side = {-5, 5};
        int beamY = Integer.MIN_VALUE;
        for (int value : side) {
            int x = cx + offsetX + (alongX ? value : 0);
            int z = cz + offsetZ + (alongX ? 0 : value);
            beamY = Math.max(beamY, naturalSurfaceY(world, x, z) + 6);
        }
        final int top = beamY;
        for (int value : side) {
            int x = cx + offsetX + (alongX ? value : 0);
            int z = cz + offsetZ + (alongX ? 0 : value);
            int ground = naturalSurfaceY(world, x, z);
            for (int y = ground + 1; y <= top; y++) {
                Material material = y == ground + 1 ? Material.CHISELED_STONE_BRICKS
                        : (y + value) % 3 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS;
                int fy = y;
                changes.add(() -> set(world, x, fy, z, material));
            }
        }
        for (int value = -5; value <= 5; value++) {
            int x = cx + offsetX + (alongX ? value : 0);
            int z = cz + offsetZ + (alongX ? 0 : value);
            changes.add(() -> set(world, x, top, z, Material.MOSSY_STONE_BRICKS));
        }
        int middleX = cx + offsetX, middleZ = cz + offsetZ;
        changes.add(() -> set(world, middleX, top - 1, middleZ, Material.IRON_CHAIN));
        changes.add(() -> set(world, middleX, top - 2, middleZ, Material.LANTERN));
    }

    private void planLanternsAndGardens(World world, List<Runnable> changes) {
        int cx = blockCenterX(), cz = blockCenterZ();
        for (int degree = 0; degree < 360; degree += 45) {
            double angle = Math.toRadians(degree + 22.5);
            int x = cx + (int) Math.round(Math.cos(angle) * 29);
            int z = cz + (int) Math.round(Math.sin(angle) * 29);
            int y = naturalSurfaceY(world, x, z);
            changes.add(() -> set(world, x, y + 1, z, Material.MOSSY_COBBLESTONE_WALL));
            changes.add(() -> set(world, x, y + 2, z, Material.SPRUCE_FENCE));
            changes.add(() -> set(world, x, y + 3, z, Material.LANTERN));
        }

        Material[] flowers = {Material.DANDELION, Material.POPPY, Material.CORNFLOWER, Material.OXEYE_DAISY,
                Material.ALLIUM, Material.AZURE_BLUET};
        for (int degree = 0; degree < 360; degree += 12) {
            double angle = Math.toRadians(degree);
            int radius = 33 + Math.floorMod(degree, 7);
            int x = cx + (int) Math.round(Math.cos(angle) * radius);
            int z = cz + (int) Math.round(Math.sin(angle) * radius);
            int y = naturalSurfaceY(world, x, z);
            Material flower = flowers[(degree / 12) % flowers.length];
            changes.add(() -> {
                Block above = world.getBlockAt(x, y + 1, z);
                if (above.getType().isAir()) above.setType(flower, false);
            });
        }
    }

    private void spawnLobbyEntities() {
        World world = world();
        if (world == null) return;
        loadEntityChunks(world);
        Location center = new Location(world, settings.hub().centerX(), settings.hub().y(), settings.hub().centerZ());
        world.getNearbyEntities(center, 80, 80, 80).stream()
                .filter(entity -> entity.getPersistentDataContainer().has(entityKey, PersistentDataType.STRING))
                .forEach(Entity::remove);

        spawnNpc(world, "guide", -10, 20, DyeColor.LIME, Material.WRITTEN_BOOK,
                Component.text("Eira • Guia", NamedTextColor.GREEN));
        spawnNpc(world, "trade", 18, 11, DyeColor.YELLOW, Material.EMERALD,
                Component.text("Mara • Trocas", NamedTextColor.GOLD));
        spawnNpc(world, "clans", -18, -10, DyeColor.RED, Material.RED_BANNER,
                Component.text("Borin • Clãs", NamedTextColor.RED));
        spawnNpc(world, "wilds", 11, -19, DyeColor.LIGHT_BLUE, Material.COMPASS,
                Component.text("Tarin • Exploração", NamedTextColor.AQUA));

        spawnLabel(world, 0, 25, 4.2, Component.text("NEMETON\n", NamedTextColor.GOLD)
                .append(Component.text("ZONA SEGURA • sem PvP • sem grife", NamedTextColor.GREEN)), "label:welcome");
        spawnExitLabel(world, 0, -58, "NORTE");
        spawnExitLabel(world, 0, 58, "SUL");
        spawnExitLabel(world, -58, 0, "OESTE");
        spawnExitLabel(world, 58, 0, "LESTE");
    }

    private void loadEntityChunks(World world) {
        int[][] offsets = {
                {-10, 20}, {18, 11}, {-18, -10}, {11, -19},
                {0, 25}, {0, -58}, {0, 58}, {-58, 0}, {58, 0}
        };
        for (int[] offset : offsets) {
            int x = blockCenterX() + offset[0];
            int z = blockCenterZ() + offset[1];
            world.getChunkAt(x >> 4, z >> 4).load();
        }
    }

    private void spawnNpc(World world, String role, int dx, int dz, DyeColor color, Material heldItem, Component name) {
        int x = blockCenterX() + dx, z = blockCenterZ() + dz;
        int y = naturalSurfaceY(world, x, z) + 1;
        Location location = new Location(world, x + 0.5, y, z + 0.5);
        world.getChunkAt(location).load();
        world.spawn(location, Mannequin.class, mannequin -> {
            mannequin.getPersistentDataContainer().set(entityKey, PersistentDataType.STRING, "npc:" + role);
            mannequin.customName(name);
            mannequin.setCustomNameVisible(true);
            mannequin.setDescription(Component.text("Clique para conversar", NamedTextColor.GRAY));
            mannequin.setImmovable(true);
            mannequin.setInvulnerable(true);
            mannequin.setSilent(true);
            mannequin.setCollidable(false);
            mannequin.setPersistent(true);
            equipNpc(mannequin.getEquipment(), color, heldItem);
        });
    }

    private void equipNpc(EntityEquipment equipment, DyeColor dyeColor, Material heldItem) {
        org.bukkit.Color color = dyeColor.getColor();
        equipment.setChestplate(leather(Material.LEATHER_CHESTPLATE, color));
        equipment.setLeggings(leather(Material.LEATHER_LEGGINGS, color));
        equipment.setBoots(leather(Material.LEATHER_BOOTS, color));
        equipment.setItemInMainHand(new ItemStack(heldItem));
    }

    private ItemStack leather(Material material, org.bukkit.Color color) {
        ItemStack item = new ItemStack(material);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        meta.setColor(color);
        item.setItemMeta(meta);
        return item;
    }

    private void spawnLabel(World world, int dx, int dz, double height, Component text, String id) {
        int x = blockCenterX() + dx, z = blockCenterZ() + dz;
        int y = naturalSurfaceY(world, x, z);
        world.spawn(new Location(world, x + 0.5, y + height, z + 0.5), TextDisplay.class, display -> {
            display.getPersistentDataContainer().set(entityKey, PersistentDataType.STRING, id);
            display.text(text);
            display.setBillboard(Display.Billboard.CENTER);
            display.setSeeThrough(false);
            display.setShadowed(true);
            display.setViewRange(0.7f);
            display.setPersistent(true);
        });
    }

    private void spawnExitLabel(World world, int dx, int dz, String direction) {
        spawnLabel(world, dx, dz, 3.2,
                Component.text("SAÍDA " + direction + "\n", NamedTextColor.YELLOW)
                        .append(Component.text("além do portal: terras selvagens", NamedTextColor.GRAY)),
                "label:exit:" + direction.toLowerCase(Locale.ROOT));
    }

    private void faceNearbyPlayers() {
        World world = world();
        if (world == null) return;
        for (Entity entity : world.getNearbyEntities(
                new Location(world, settings.hub().centerX(), settings.hub().y(), settings.hub().centerZ()), 50, 30, 50)) {
            String role = entity.getPersistentDataContainer().get(entityKey, PersistentDataType.STRING);
            if (!(entity instanceof Mannequin mannequin) || role == null || !role.startsWith("npc:")) continue;
            Player nearest = world.getNearbyPlayers(mannequin.getLocation(), 8).stream()
                    .min(Comparator.comparingDouble(player -> player.getLocation().distanceSquared(mannequin.getLocation())))
                    .orElse(null);
            if (nearest == null) continue;
            Location look = mannequin.getLocation();
            double dx = nearest.getX() - look.getX(), dz = nearest.getZ() - look.getZ();
            look.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            mannequin.teleport(look);
        }
    }

    private void clearPlants(World world, List<Runnable> changes, int x, int y, int z) {
        changes.add(() -> {
            Block block = world.getBlockAt(x, y, z);
            if (block.isPassable() && block.getType() != Material.WATER && block.getType() != Material.LAVA) {
                block.setType(Material.AIR, false);
            }
        });
    }

    private int naturalSurfaceY(World world, int x, int z) {
        int maximum = Math.min(world.getMaxHeight() - 2, 110);
        for (int y = maximum; y >= world.getMinHeight(); y--) {
            Material material = world.getBlockAt(x, y, z).getType();
            if (isGround(material)) return y;
        }
        return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    }

    private boolean isGround(Material material) {
        return switch (material) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, ROOTED_DIRT, PODZOL, MYCELIUM, MOSS_BLOCK,
                    STONE, ANDESITE, DIORITE, GRANITE, SAND, RED_SAND, GRAVEL, CLAY,
                    DIRT_PATH, PACKED_MUD, MUD_BRICKS, COBBLESTONE, MOSSY_COBBLESTONE,
                    STONE_BRICKS, MOSSY_STONE_BRICKS -> true;
            default -> false;
        };
    }

    private void set(World world, int x, int y, int z, Material material) {
        world.getBlockAt(x, y, z).setType(material, false);
    }

    private World world() { return Bukkit.getWorld(settings.hub().world()); }
    private int blockCenterX() { return (int) Math.floor(settings.hub().centerX()); }
    private int blockCenterZ() { return (int) Math.floor(settings.hub().centerZ()); }
}
