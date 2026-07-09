package dev.nemeton.service;

import dev.nemeton.config.Settings;
import dev.nemeton.integration.BedrockForms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCLeftClickEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.*;
import org.bukkit.Axis;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

/** Owns the visible and interactive experience of the Nemeton clearing. */
public final class LobbyService implements Listener, CommandExecutor {
    private static final int CHANGES_PER_TICK = 650;
    private static final int MAX_VISUAL_RADIUS = 44;
    private static final int MIN_VISUAL_RADIUS = 30;
    private static final int TREE_COPY_RADIUS = 44;
    private static final int TREE_COPY_BELOW = 12;
    private static final int TREE_COPY_ABOVE = 78;
    private static final int NEMETON_TREE_HEIGHT = 62;
    private static final int NEMETON_TREE_BASE_RADIUS = 10;
    private static final String CITIZENS_ROLE = "nemeton-role";

    private record TreeBlock(int dx, int dy, int dz, BlockData data) { }

    private final JavaPlugin plugin;
    private final Settings settings;
    private final NamespacedKey entityKey;
    private final Map<UUID, Boolean> safeState = new HashMap<>();
    private final Map<UUID, Long> npcClickCooldown = new HashMap<>();
    private boolean building;

    public LobbyService(JavaPlugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.entityKey = new NamespacedKey(plugin, "lobby_entity");
        Bukkit.getScheduler().runTaskLater(plugin, this::spawnLobbyEntities, 200L);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            World world = world();
            if (world != null) removeLobbyMobs(world);
        }, 40L, 40L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> safeState.put(event.getPlayer().getUniqueId(), isInside(event.getPlayer().getLocation())), 10L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (isInside(event.getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLivingEntitySpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof LivingEntity && !(event.getEntity() instanceof Player)
                && isInside(event.getLocation())) event.setCancelled(true);
    }

    @EventHandler
    public void onServerLoaded(ServerLoadEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, this::spawnLobbyEntities, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNpcRightClick(NPCRightClickEvent event) {
        if (handleCitizensNpc(event.getClicker(), event.getNPC())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNpcLeftClick(NPCLeftClickEvent event) {
        if (handleCitizensNpc(event.getClicker(), event.getNPC())) event.setCancelled(true);
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
        if (handleNpcInteraction(event.getPlayer(), event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onNpcInteractAt(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (handleNpcInteraction(event.getPlayer(), event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onNpcArmorManipulate(PlayerArmorStandManipulateEvent event) {
        if (event.getRightClicked().getPersistentDataContainer().has(entityKey, PersistentDataType.STRING)) {
            event.setCancelled(true);
            handleNpcInteraction(event.getPlayer(), event.getRightClicked());
        }
    }

    private boolean handleNpcInteraction(Player player, Entity entity) {
        String role = entity.getPersistentDataContainer().get(entityKey, PersistentDataType.STRING);
        if (role == null || !role.startsWith("npc:")) return false;
        return handleNpcRole(player, role.substring(4));
    }

    private boolean handleCitizensNpc(Player player, NPC npc) {
        String role = npc.data().get(CITIZENS_ROLE);
        if (role == null || !isInside(player.getLocation())) return false;
        long now = System.currentTimeMillis();
        if (npcClickCooldown.getOrDefault(player.getUniqueId(), 0L) > now) return true;
        npcClickCooldown.put(player.getUniqueId(), now + 600L);
        return handleNpcRole(player, role);
    }

    private boolean handleNpcRole(Player player, String role) {
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.8f, 1.1f);
        if (sendNpcForm(player, role)) return true;
        switch (role) {
            case "guide" -> {
                npcCard(player, "§a§lEira, Guia do Nemeton",
                        "§7Aqui é a zona segura.",
                        "§f/guia §7mostra o livro inicial.",
                        "§f/kit §7entrega ferramentas de começo.",
                        "§f/mapa §7abre orientação Java/Bedrock.");
            }
            case "clans" -> {
                npcCard(player, "§c§lBorin, Mestre dos Clãs",
                        "§7Crie ou entre em uma equipe para jogar junto.",
                        "§f/clan criar <nome> <tag>",
                        "§f/clan convidar <jogador>",
                        "§f/clan claim §7protege território conectado.",
                        "§f/raid status §7mostra conflitos futuros.");
            }
            case "trade" -> {
                npcCard(player, "§6§lMara, Mercadora",
                        "§7Negocie sem loja infinita e sem moeda virtual.",
                        "§f/troca <jogador> §7abre troca segura.",
                        "§7No Java abre uma interface.",
                        "§7No Bedrock abre uma tela nativa com botões.");
            }
            case "wilds" -> {
                npcCard(player, "§b§lTarin, Batedor",
                        "§7Depois dos portões começa o survival.",
                        "§f/santuario marcar §7protege sua base pessoal.",
                        "§f/lapide §7aponta para sua última morte.",
                        "§f/mochila §7abre sua bolsa pessoal.");
            }
            case "mods" -> {
                npcCard(player, "§d§lNara, Artesã do Nemeton+",
                        "§7Aqui ficam as adições Vanilla+ autorais.",
                        "§f/mods §7explica minimap e packs opcionais.",
                        "§f/mods itens §7lista armas, armaduras e drops.",
                        "§7No Lunar: Right Shift → Mods → Minimap.");
            }
            default -> { }
        }
        return true;
    }

    private boolean sendNpcForm(Player player, String role) {
        String title;
        String content;
        String[] buttons;
        switch (role) {
            case "guide" -> {
                title = "Eira • Guia";
                content = "Zona segura do Nemeton.\n\n/guia: livro inicial\n/kit: ferramentas de começo\n/mapa: orientação Java/Bedrock";
                buttons = new String[]{"Receber /guia", "Pegar /kit", "Abrir /mapa", "Fechar"};
            }
            case "clans" -> {
                title = "Borin • Clãs e Raids";
                content = "Crie ou entre em uma equipe.\n\n/clan criar <nome> <tag>\n/clan convidar <jogador>\n/clan claim protege território conectado.\n/raid status mostra conflitos futuros.";
                buttons = new String[]{"Ajuda de clã", "Status de raid", "Fechar"};
            }
            case "trade" -> {
                title = "Mara • Trocas";
                content = "Negocie sem banco virtual.\n\nJava usa interface de inventário.\nBedrock usa uma tela nativa com botões: oferecer item da mão, aceitar, limpar e cancelar.";
                buttons = new String[]{"Abrir troca", "Ver troca atual", "Cancelar troca atual", "Fechar"};
            }
            case "wilds" -> {
                title = "Tarin • Exploração";
                content = "Depois dos portões começa o survival.\n\n/santuario marcar protege sua base.\n/lapide aponta para sua morte.\n/mochila abre sua bolsa pessoal.";
                buttons = new String[]{"Ajuda santuário", "Abrir mochila", "Ver lápide", "Fechar"};
            }
            case "mods" -> {
                title = "Nara • Nemeton+";
                content = "Conteúdo Vanilla+ autoral.\n\nMinimap no Lunar: Right Shift > Mods > Minimap.\nBedrock usa /mapa e forms nativos.\nItens especiais vêm de mineração, Wither e Dragão.";
                buttons = new String[]{"Abrir /mods", "Ver itens Nemeton+", "Fechar"};
            }
            default -> { return false; }
        }
        return BedrockForms.sendSimple(plugin, player, title, content, index -> handleNpcButton(player, role, index), buttons);
    }

    private void handleNpcButton(Player player, String role, int index) {
        switch (role) {
            case "guide" -> {
                if (index == 0) player.performCommand("guia");
                else if (index == 1) player.performCommand("kit");
                else if (index == 2) player.performCommand("mapa");
            }
            case "clans" -> {
                if (index == 0) player.performCommand("clan ajuda");
                else if (index == 1) player.performCommand("raid status");
            }
            case "trade" -> {
                if (index == 0) player.performCommand("troca");
                else if (index == 1) player.performCommand("troca ver");
                else if (index == 2) player.performCommand("troca cancelar");
            }
            case "wilds" -> {
                if (index == 0) player.performCommand("santuario ajuda");
                else if (index == 1) player.performCommand("mochila");
                else if (index == 2) player.performCommand("lapide");
            }
            case "mods" -> {
                if (index == 0) player.performCommand("mods");
                else if (index == 1) player.performCommand("mods itens");
            }
            default -> { }
        }
    }

    private void npcCard(Player player, String title, String... lines) {
        player.sendMessage("§8§m                                                ");
        player.sendMessage(title);
        for (String line : lines) player.sendMessage(line);
        player.sendMessage("§8§m                                                ");
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
        if (args[0].equalsIgnoreCase("selar")) {
            sealSubsoil(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("avaliar")) {
            evaluateTerrain(sender, args);
            return true;
        }
        if (args[0].equalsIgnoreCase("setspawn")) {
            setSpawn(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("copiararvore")) {
            copyTree(sender, args);
            return true;
        }
        if (args[0].equalsIgnoreCase("construir")) {
            buildLobby(sender);
            return true;
        }
        sender.sendMessage("Use /nemetonadmin construir|selar|npcs|status|setspawn|avaliar|copiararvore");
        return true;
    }

    private void copyTree(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§eUse /nemetonadmin copiararvore <x> <y-do-solo> <z>");
            return;
        }
        if (building) {
            sender.sendMessage("§eUma construção do Nemeton já está em andamento.");
            return;
        }
        World world = world();
        if (world == null) {
            sender.sendMessage("§cMundo do Nemeton não carregado.");
            return;
        }

        final int targetX;
        final int targetY;
        final int targetZ;
        final boolean correction = args.length >= 5 && args[4].equalsIgnoreCase("corrigir");
        final boolean moving = args.length >= 5 && args[4].equalsIgnoreCase("mover");
        final int previousBaseY;
        try {
            targetX = Integer.parseInt(args[1]);
            targetY = Integer.parseInt(args[2]);
            targetZ = Integer.parseInt(args[3]);
            previousBaseY = moving && args.length >= 6 ? Integer.parseInt(args[5]) : targetY;
        } catch (NumberFormatException exception) {
            sender.sendMessage("§cAs coordenadas precisam ser números inteiros.");
            return;
        }
        if (moving && args.length < 6) {
            sender.sendMessage("§eUse /nemetonadmin copiararvore <x> <novo-y> <z> mover <y-atual>");
            return;
        }
        if (targetY - TREE_COPY_BELOW <= world.getMinHeight()
                || targetY + TREE_COPY_ABOVE >= world.getMaxHeight()) {
            sender.sendMessage("§cA árvore não cabe nessa altura.");
            return;
        }

        int sourceX = blockCenterX();
        int sourceZ = blockCenterZ();
        int sourceGround = terrainSurfaceY(world, sourceX, sourceZ);
        int width = TREE_COPY_RADIUS * 2 + 1;
        int height = TREE_COPY_BELOW + TREE_COPY_ABOVE + 1;
        int layerSize = width * width;
        int scanSize = layerSize * height;
        List<TreeBlock> blocks = new ArrayList<>();
        building = true;
        String operation = moving ? "Movendo" : correction ? "Corrigindo" : "Copiando";
        sender.sendMessage("§6" + operation + " somente a árvore do Nemeton em §f"
                + targetX + " " + targetY + " " + targetZ
                + "§6. O bloco mais baixo da árvore ficará exatamente no Y informado.");

        new BukkitRunnable() {
            private int scanIndex;
            private boolean scanFinished;
            private Iterator<TreeBlock> cleanup;
            private Iterator<TreeBlock> placement;
            private int minimumDy;
            private int removed;
            private int placed;
            private int skipped;

            @Override public void run() {
                if (!scanFinished) {
                    int scanned = 0;
                    while (scanIndex < scanSize && scanned++ < 6000) {
                        int yIndex = scanIndex / layerSize;
                        int remainder = scanIndex % layerSize;
                        int dx = remainder / width - TREE_COPY_RADIUS;
                        int dz = remainder % width - TREE_COPY_RADIUS;
                        int dy = yIndex - TREE_COPY_BELOW;
                        scanIndex++;

                        Block source = world.getBlockAt(sourceX + dx, sourceGround + dy, sourceZ + dz);
                        if (!isTreeReplicaMaterial(source.getType())) continue;
                        blocks.add(new TreeBlock(dx, dy, dz, source.getBlockData().clone()));
                    }
                    if (scanIndex < scanSize) return;
                    scanFinished = true;
                    minimumDy = blocks.stream().mapToInt(TreeBlock::dy).min().orElse(0);
                    cleanup = correction || moving ? blocks.iterator() : Collections.emptyIterator();
                    sender.sendMessage("§7Árvore isolada: " + blocks.size() + " blocos. Referência vertical corrigida em "
                            + (-minimumDy) + " blocos; iniciando montagem em lotes seguros.");
                }

                int cleaned = 0;
                while (cleanup.hasNext() && cleaned++ < CHANGES_PER_TICK) {
                    TreeBlock treeBlock = cleanup.next();
                    int oldY = moving
                            ? previousBaseY + treeBlock.dy() - minimumDy
                            : targetY + treeBlock.dy();
                    Block oldTarget = world.getBlockAt(
                            targetX + treeBlock.dx(), oldY, targetZ + treeBlock.dz());
                    if (oldTarget.getType() != treeBlock.data().getMaterial()) continue;
                    oldTarget.setType(Material.AIR, false);
                    removed++;
                }
                if (cleanup.hasNext()) return;
                if (placement == null) placement = blocks.iterator();

                int changed = 0;
                while (placement.hasNext() && changed++ < CHANGES_PER_TICK) {
                    TreeBlock treeBlock = placement.next();
                    Block target = world.getBlockAt(targetX + treeBlock.dx(),
                            targetY + treeBlock.dy() - minimumDy, targetZ + treeBlock.dz());
                    if (!canPlaceTreeReplica(target.getType(), treeBlock.data().getMaterial())) {
                        skipped++;
                        continue;
                    }
                    target.setBlockData(treeBlock.data(), false);
                    placed++;
                }
                if (placement.hasNext()) return;

                cancel();
                building = false;
                world.save();
                String result = "Árvore do Nemeton concluída em " + targetX + " " + targetY + " " + targetZ
                        + ": base real no Y " + targetY + ", " + removed + " blocos antigos removidos, "
                        + placed + " blocos colocados, " + skipped + " preservados por haver construção no caminho.";
                sender.sendMessage("§a" + result);
                plugin.getLogger().info(result);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private boolean isTreeReplicaMaterial(Material material) {
        return switch (material) {
            case DARK_OAK_LOG, STRIPPED_DARK_OAK_LOG, SPRUCE_LOG,
                    OAK_LEAVES, DARK_OAK_LEAVES, AZALEA_LEAVES, FLOWERING_AZALEA_LEAVES,
                    HANGING_ROOTS, SEA_LANTERN, SHROOMLIGHT -> true;
            default -> false;
        };
    }

    private boolean canPlaceTreeReplica(Material existing, Material treeMaterial) {
        boolean leaves = treeMaterial.name().endsWith("_LEAVES");
        if (leaves) return existing.isAir() || existing.name().endsWith("_LEAVES");
        if (existing.isAir() || existing.name().endsWith("_LEAVES")) return true;
        return !existing.isSolid() && existing != Material.WATER && existing != Material.LAVA;
    }

    private void setSpawn(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§eUse este comando dentro do jogo, no ponto exato do novo spawn.");
            return;
        }
        Location location = player.getLocation();
        World world = location.getWorld();
        if (world == null) {
            sender.sendMessage("§cMundo inválido.");
            return;
        }
        if (!world.getName().equals(settings.hub().world())) {
            sender.sendMessage("§cO spawn do Nemeton precisa ficar no mundo " + settings.hub().world() + ".");
            return;
        }

        double x = centered(location.getX());
        double y = location.getY();
        double z = centered(location.getZ());
        float yaw = location.getYaw();
        float pitch = location.getPitch();

        plugin.getConfig().set("nemeton.x", x);
        plugin.getConfig().set("nemeton.y", y);
        plugin.getConfig().set("nemeton.z", z);
        plugin.getConfig().set("nemeton.yaw", (double) yaw);
        plugin.getConfig().set("nemeton.pitch", (double) pitch);
        plugin.saveConfig();

        settings.updateHub(new Settings.Hub(settings.hub().world(), x, y, z, yaw, pitch,
                settings.hub().centerX(), settings.hub().centerZ(), settings.hub().radius(),
                settings.hub().warmup(), settings.hub().cooldown()));
        world.setSpawnLocation(location.getBlockX(), location.getBlockY(), location.getBlockZ(), yaw);
        player.setCompassTarget(new Location(world, settings.hub().centerX(), y, settings.hub().centerZ()));
        sender.sendMessage("§aSpawn do Nemeton atualizado para §f" + world.getName() + " "
                + String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", x, y, z) + "§a.");
        sender.sendMessage("§7Agora /spawn, /nemeton, respawn e primeiro login usam este ponto.");
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
        removeLobbyMobs(world);
        planSealSubsoil(world, changes);
        planCleanClearing(world, changes);
        planCoreTree(world, changes);
        planPaths(world, changes);
        planNpcPavilions(world, changes);
        planLanternsAndGardens(world, changes);
        planBoundary(world, changes);
        planGateways(world, changes);
        planBeaconBase(world, changes);
        sender.sendMessage("§6Reconstrução cinematográfica do Nemeton iniciada: " + changes.size() + " alterações em lotes seguros.");

        Iterator<Runnable> iterator = changes.iterator();
        new BukkitRunnable() {
            @Override public void run() {
                int count = 0;
                while (iterator.hasNext() && count++ < CHANGES_PER_TICK) iterator.next().run();
                if (iterator.hasNext()) return;
                cancel();
                building = false;
                activateBeacon(world);
                spawnLobbyEntities();
                world.save();
                sender.sendMessage("§aReforma concluída. Árvore colossal, beacon ativo, clareira, estações, portais e NPCs estão prontos.");
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void sealSubsoil(CommandSender sender) {
        if (building) {
            sender.sendMessage("§eUma manutenção do Nemeton já está em andamento.");
            return;
        }
        World world = world();
        if (world == null) {
            sender.sendMessage("§cMundo do Nemeton não carregado.");
            return;
        }
        building = true;
        removeLobbyMobs(world);
        List<Runnable> changes = new ArrayList<>();
        planSealSubsoil(world, changes);
        sender.sendMessage("§6Selagem do subsolo iniciada: " + changes.size() + " cavidades serão preenchidas.");
        Iterator<Runnable> iterator = changes.iterator();
        new BukkitRunnable() {
            @Override public void run() {
                int count = 0;
                while (iterator.hasNext() && count++ < 1200) iterator.next().run();
                if (iterator.hasNext()) return;
                cancel();
                building = false;
                world.save();
                sender.sendMessage("§aSubsolo do Nemeton totalmente selado e salvo.");
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void planSealSubsoil(World world, List<Runnable> changes) {
        int cx = blockCenterX(), cz = blockCenterZ();
        int radius = settings.hub().radius();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                if (Math.hypot(x + 0.5 - settings.hub().centerX(), z + 0.5 - settings.hub().centerZ()) > radius) continue;
                int surface = subsoilSurfaceY(world, x, z);
                for (int y = world.getMinHeight(); y < surface; y++) {
                    if (world.getBlockAt(x, y, z).getType().isSolid()) continue;
                    int fx = x, fy = y, fz = z;
                    Material fill = y < 0 ? Material.DEEPSLATE : Material.STONE;
                    changes.add(() -> set(world, fx, fy, fz, fill));
                }
            }
        }
    }

    private int subsoilSurfaceY(World world, int x, int z) {
        int nominal = (int) Math.floor(settings.hub().y()) - 1;
        for (int y = Math.min(world.getMaxHeight() - 2, nominal + 10); y >= nominal - 24; y--) {
            Material material = world.getBlockAt(x, y, z).getType();
            if (isLobbyGroundSurface(material)) return y;
        }
        return nominal;
    }

    private boolean isLobbyGroundSurface(Material material) {
        return switch (material) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, ROOTED_DIRT, PODZOL, MYCELIUM, MOSS_BLOCK,
                    DIRT_PATH, PACKED_MUD, MUD_BRICKS, MOSSY_COBBLESTONE -> true;
            default -> false;
        };
    }

    private void evaluateTerrain(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§eUse /nemetonadmin avaliar <x> <z> [raio]");
            return;
        }
        World world = world();
        if (world == null) {
            sender.sendMessage("§cMundo do Nemeton não carregado.");
            return;
        }
        int centerX = Integer.parseInt(args[1]);
        int centerZ = Integer.parseInt(args[2]);
        int radius = args.length >= 4 ? Math.max(32, Math.min(384, Integer.parseInt(args[3]))) : 128;
        int step = radius <= 96 ? 16 : 32;
        int samples = 0, water = 0, open = 0, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        Map<Biome, Integer> biomes = new HashMap<>();
        Map<Material, Integer> surfaces = new EnumMap<>(Material.class);
        for (int x = centerX - radius; x <= centerX + radius; x += step) {
            for (int z = centerZ - radius; z <= centerZ + radius; z += step) {
                Block top = world.getHighestBlockAt(x, z, HeightMap.WORLD_SURFACE);
                Material surface = top.getType();
                Biome biome = top.getBiome();
                samples++;
                minY = Math.min(minY, top.getY());
                maxY = Math.max(maxY, top.getY());
                biomes.merge(biome, 1, Integer::sum);
                surfaces.merge(surface, 1, Integer::sum);
                if (isWaterSurface(surface)) water++;
                if (isOpenLobbyBiome(biome)) open++;
            }
        }
        sender.sendMessage("§6Avaliação " + centerX + ", " + centerZ + " raio " + radius
                + "§7 — amostras: " + samples
                + ", água: " + percent(water, samples)
                + ", aberto: " + percent(open, samples)
                + ", altura: " + minY + "–" + maxY + " (Δ" + (maxY - minY) + ")");
        sender.sendMessage("§7Biomas: " + topEntries(biomes));
        sender.sendMessage("§7Superfície: " + topEntries(surfaces));
    }

    private void planCleanClearing(World world, List<Runnable> changes) {
        int cx = blockCenterX(), cz = blockCenterZ();
        int radius = visualRadius() + 5;
        int maxY = Math.min(world.getMaxHeight() - 2, lobbyBaseY(world) + NEMETON_TREE_HEIGHT + 32);
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                double distance = Math.hypot(x - cx, z - cz);
                if (distance > radius) continue;
                int targetY = plannedSurfaceY(world, x, z);
                int fx = x, fz = z, fy = targetY;
                int fillBottom = Math.max(world.getMinHeight(), targetY - 18);
                for (int y = fillBottom; y <= targetY; y++) {
                    int cy = y;
                    changes.add(() -> {
                        Material material = cy == fy ? sculptedSurfaceMaterial(fx, fz, distance)
                                : cy >= fy - 3 ? (Math.floorMod(fx * 13 + fz * 7 + cy, 9) == 0 ? Material.ROOTED_DIRT : Material.DIRT)
                                : Material.STONE;
                        set(world, fx, cy, fz, material);
                    });
                }
                for (int y = targetY + 1; y <= maxY; y++) {
                    int cy = y;
                    changes.add(() -> {
                        Block block = world.getBlockAt(fx, cy, fz);
                        if (shouldClearLobbySculpture(block.getType())) block.setType(Material.AIR, false);
                    });
                }
                if (distance <= visualRadius() + 2) clearPlants(world, changes, x, targetY + 1, z);
            }
        }
    }

    private Material sculptedSurfaceMaterial(int x, int z, double distance) {
        int noise = Math.floorMod(x * 37 + z * 19, 17);
        if (distance <= 9.5) {
            if (noise == 0) return Material.ROOTED_DIRT;
            return noise <= 4 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK;
        }
        if (distance <= 18.5) {
            if (noise <= 2) return Material.MOSS_BLOCK;
            if (noise == 3) return Material.ROOTED_DIRT;
            return Material.GRASS_BLOCK;
        }
        if (distance <= visualRadius() - 5) {
            if (noise <= 2) return Material.MOSS_BLOCK;
            if (noise == 3) return Material.PODZOL;
            if (noise == 4) return Material.COARSE_DIRT;
            return Material.GRASS_BLOCK;
        }
        if (noise <= 2) return Material.MOSS_BLOCK;
        if (noise <= 4) return Material.PODZOL;
        return Material.GRASS_BLOCK;
    }

    private boolean isWaterSurface(Material material) {
        return material == Material.WATER || material == Material.SEAGRASS || material == Material.TALL_SEAGRASS
                || material == Material.KELP || material == Material.KELP_PLANT || material == Material.BUBBLE_COLUMN
                || material == Material.ICE || material == Material.FROSTED_ICE || material == Material.BLUE_ICE
                || material == Material.PACKED_ICE;
    }

    private boolean isOpenLobbyBiome(Biome biome) {
        String name = biome.name();
        return name.contains("PLAINS") || name.contains("MEADOW") || name.contains("SAVANNA")
                || name.contains("CHERRY_GROVE") || name.contains("FOREST");
    }

    private String percent(int value, int total) {
        return String.format(Locale.ROOT, "%.1f%%", total == 0 ? 0.0 : value * 100.0 / total);
    }

    private <T> String topEntries(Map<T, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<T, Integer>comparingByValue().reversed())
                .limit(4)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + ", " + right)
                .orElse("nenhum");
    }

    private void planCoreTree(World world, List<Runnable> changes) {
        int cx = blockCenterX(), cz = blockCenterZ();
        int ground = lobbyBaseY(world);
        int height = NEMETON_TREE_HEIGHT;

        for (int y = ground + 1; y <= ground + height; y++) {
            int local = y - ground;
            double t = local / (double) height;
            double radius = NEMETON_TREE_BASE_RADIUS - t * 5.2
                    + Math.sin(local * 0.33) * 0.55
                    + (local < 14 ? 1.1 : 0.0);
            int limit = (int) Math.ceil(radius + 1.6);
            for (int dx = -limit; dx <= limit; dx++) {
                for (int dz = -limit; dz <= limit; dz++) {
                    double twist = Math.sin(local * 0.18) * 0.8;
                    double distance = Math.hypot(dx + twist * 0.35, dz - twist * 0.25);
                    double noise = (Math.floorMod((cx + dx) * 31 + (cz + dz) * 17 + y * 7, 13) - 6) * 0.12;
                    if (distance > radius + noise) continue;

                    boolean centralBeam = Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
                    boolean hollowChamber = local <= 16 && distance < 3.5;
                    boolean doorway = local <= 8 && distance < radius - 0.8
                            && ((Math.abs(dx) <= 2 && Math.abs(dz) >= 4)
                            || (Math.abs(dz) <= 2 && Math.abs(dx) >= 4));
                    if (centralBeam || hollowChamber || doorway) continue;

                    Material material = Math.floorMod(dx * 3 + dz * 5 + y, 9) == 0 ? Material.STRIPPED_DARK_OAK_LOG
                            : Math.floorMod(dx + dz + y, 7) == 0 ? Material.SPRUCE_LOG
                            : Material.DARK_OAK_LOG;
                    changes.add(log(world, cx + dx, y, cz + dz, material, Axis.Y));
                }
            }
        }

        for (int degree = 0; degree < 360; degree += 18) {
            double angle = Math.toRadians(degree);
            int length = degree % 90 == 0 ? 39 : degree % 45 == 0 ? 34 : 27;
            int width = degree % 45 == 0 ? 3 : 2;
            planGroundRoot(world, changes, cx, cz, ground, angle, length, width);
        }

        for (int degree = 0; degree < 360; degree += 30) {
            double angle = Math.toRadians(degree + 8);
            int startY = ground + 19 + Math.floorMod(degree, 17);
            int length = degree % 60 == 0 ? 34 : 26;
            int lift = degree % 90 == 0 ? 13 : 9;
            planBranch(world, changes, cx, cz, startY, angle, length, lift, degree % 60 == 0 ? 7 : 5);
        }
        for (int degree = 15; degree < 360; degree += 45) {
            double angle = Math.toRadians(degree);
            planBranch(world, changes, cx, cz, ground + 38 + Math.floorMod(degree, 9), angle, 22, 10, 6);
        }

        Material[] leafPalette = {
                Material.OAK_LEAVES, Material.DARK_OAK_LEAVES, Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES
        };
        for (int y = ground + 30; y <= ground + height + 16; y++) {
            double vertical = Math.abs((ground + 54.0) - y);
            double radius = 37.0 - vertical * 0.82;
            if (y < ground + 42) radius += 4.0;
            if (y > ground + 66) radius -= (y - (ground + 66)) * 0.35;
            if (radius < 4.5) continue;
            int limit = (int) Math.ceil(radius);
            for (int dx = -limit; dx <= limit; dx++) {
                for (int dz = -limit; dz <= limit; dz++) {
                    double stretched = Math.hypot(dx * 0.94, dz * 1.06);
                    double noise = Math.floorMod((cx + dx) * 31 + (cz + dz) * 17 + y * 13, 17) * 0.16;
                    if (stretched > radius + noise) continue;
                    if (Math.abs(dx) <= 2 && Math.abs(dz) <= 2) continue;
                    if (Math.floorMod(dx * 13 + dz * 19 + y * 7, 37) == 0) continue;
                    Material leaf = leafPalette[Math.floorMod(dx * 5 + dz * 3 + y, leafPalette.length)];
                    changes.add(leaves(world, cx + dx, y, cz + dz, leaf));
                }
            }
        }

        for (int degree = 0; degree < 360; degree += 15) {
            double angle = Math.toRadians(degree + 5);
            int radius = degree % 45 == 0 ? 18 : 27;
            int x = cx + (int) Math.round(Math.cos(angle) * radius);
            int z = cz + (int) Math.round(Math.sin(angle) * radius);
            int y = ground + 35 + Math.floorMod(degree, 19);
            Material light = degree % 60 == 0 ? Material.SEA_LANTERN : Material.SHROOMLIGHT;
            changes.add(() -> set(world, x, y, z, light));
            int roots = 3 + Math.floorMod(degree, 6);
            for (int drop = 1; drop <= roots; drop++) {
                int hy = y - drop;
                changes.add(placeIfAir(world, x, hy, z, drop == roots ? Material.GLOW_LICHEN : Material.HANGING_ROOTS));
            }
        }
    }

    private void planGroundRoot(World world, List<Runnable> changes, int cx, int cz, int ground,
                                double angle, int length, int width) {
        for (int step = 0; step <= length; step++) {
            double curve = Math.sin(step * 0.24 + angle * 2.0) * 0.16;
            double currentAngle = angle + curve;
            int distance = NEMETON_TREE_BASE_RADIUS - 2 + step;
            int x = cx + (int) Math.round(Math.cos(currentAngle) * distance);
            int z = cz + (int) Math.round(Math.sin(currentAngle) * distance);
            int thickness = Math.max(0, width - step / 13);
            Axis axis = Math.abs(Math.cos(currentAngle)) >= Math.abs(Math.sin(currentAngle)) ? Axis.X : Axis.Z;
            for (int lateral = -thickness; lateral <= thickness; lateral++) {
                int px = x + (int) Math.round(-Math.sin(currentAngle) * lateral);
                int pz = z + (int) Math.round(Math.cos(currentAngle) * lateral);
                int py = plannedSurfaceY(world, px, pz);
                int fx = px, fy = py, fz = pz;
                changes.add(() -> set(world, fx, fy, fz,
                        Math.floorMod(fx * 11 + fz * 5, 6) == 0 ? Material.ROOTED_DIRT : Material.MOSS_BLOCK));
                if (step < length - 2 && Math.abs(lateral) <= Math.max(0, thickness - 1)) {
                    changes.add(log(world, px, py + 1, pz,
                            Math.floorMod(step + lateral, 5) == 0 ? Material.STRIPPED_DARK_OAK_LOG : Material.DARK_OAK_LOG,
                            axis));
                }
                if (step < length / 2 && thickness >= 2 && lateral == 0 && step % 4 == 0) {
                    changes.add(log(world, px, py + 2, pz, Material.SPRUCE_LOG, axis));
                }
            }
        }
    }

    private void planBranch(World world, List<Runnable> changes, int cx, int cz, int startY,
                            double angle, int length, int lift, int clusterRadius) {
        Axis axis = Math.abs(Math.cos(angle)) >= Math.abs(Math.sin(angle)) ? Axis.X : Axis.Z;
        int endX = cx, endY = startY, endZ = cz;
        for (int step = 0; step <= length; step++) {
            double sway = Math.sin(step * 0.31 + angle * 3.0) * 0.12;
            double currentAngle = angle + sway;
            double distance = 7.0 + step;
            int x = cx + (int) Math.round(Math.cos(currentAngle) * distance);
            int z = cz + (int) Math.round(Math.sin(currentAngle) * distance);
            int y = startY + (int) Math.round(step * (lift / (double) Math.max(1, length)))
                    + (int) Math.round(Math.sin(step * 0.43) * 1.2);
            endX = x; endY = y; endZ = z;
            changes.add(log(world, x, y, z,
                    step % 5 == 0 ? Material.STRIPPED_DARK_OAK_LOG : Material.DARK_OAK_LOG, axis));
            if (step < 10) {
                changes.add(log(world, x, y - 1, z, Material.SPRUCE_LOG, axis));
                if (step % 2 == 0) changes.add(log(world, x, y + 1, z, Material.DARK_OAK_LOG, axis));
            }
            if (step > 7 && step % 7 == 0) planLeafCluster(changes, world, x, y, z, 3);
        }
        planLeafCluster(changes, world, endX, endY, endZ, clusterRadius);
        planLeafCluster(changes, world, endX - (int) Math.round(Math.sin(angle) * 4), endY - 1,
                endZ + (int) Math.round(Math.cos(angle) * 4), Math.max(3, clusterRadius - 1));
    }

    private void planBeaconBase(World world, List<Runnable> changes) {
        int cx = blockCenterX(), cz = blockCenterZ();
        int beaconY = beaconY();
        Material[] pyramid = {Material.IRON_BLOCK, Material.GOLD_BLOCK, Material.EMERALD_BLOCK, Material.DIAMOND_BLOCK};
        for (int layer = 0; layer < 4; layer++) {
            int half = 4 - layer;
            int y = beaconY - 4 + layer;
            Material material = pyramid[layer];
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    int x = cx + dx, z = cz + dz;
                    changes.add(() -> set(world, x, y, z, material));
                }
            }
        }
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                double distance = Math.hypot(dx, dz);
                if (distance < 4.5 || distance > 5.7) continue;
                int x = cx + dx, z = cz + dz;
                changes.add(() -> set(world, x, beaconY - 1, z,
                        Math.floorMod(x * 17 + z * 7, 3) == 0 ? Material.POLISHED_DEEPSLATE : Material.SMOOTH_BASALT));
            }
        }
        changes.add(() -> set(world, cx, beaconY, cz, Material.BEACON));
        clearColumn(world, changes, cx, cz, beaconY + 1, world.getMaxHeight() - 2);
        changes.add(() -> set(world, cx, beaconY + 2, cz, Material.PURPLE_STAINED_GLASS));
        changes.add(() -> set(world, cx, beaconY + 3, cz, Material.MAGENTA_STAINED_GLASS));
        changes.add(() -> set(world, cx, beaconY + 4, cz, Material.BLUE_STAINED_GLASS));
    }

    private void planLeafCluster(List<Runnable> changes, World world, int cx, int cy, int cz, int radius) {
        Material[] palette = {Material.OAK_LEAVES, Material.DARK_OAK_LEAVES, Material.FLOWERING_AZALEA_LEAVES};
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.hypot(Math.hypot(dx, dz), dy * 0.8) > radius + 0.35) continue;
                    if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && dy > 0) continue;
                    Material leaf = palette[Math.floorMod(cx + dx * 7 + cy + dy * 11 + cz + dz * 13, palette.length)];
                    changes.add(leaves(world, cx + dx, cy + dy, cz + dz, leaf));
                }
            }
        }
    }

    private void planPaths(World world, List<Runnable> changes) {
        int cx = blockCenterX(), cz = blockCenterZ();
        int radius = visualRadius();
        int gate = gateOffset();
        int inner = Math.max(16, radius / 3 + 3);
        int outer = Math.max(28, radius - 10);
        for (int x = cx - radius - 2; x <= cx + radius + 2; x++) {
            for (int z = cz - radius - 2; z <= cz + radius + 2; z++) {
                int dx = x - cx, dz = z - cz;
                double distance = Math.hypot(dx, dz);
                boolean centralSanctum = distance <= 11.5;
                boolean innerRing = distance >= inner && distance <= inner + 3.8;
                boolean outerRing = distance >= outer && distance <= outer + 2.2;
                boolean northSouth = Math.abs(dx) <= 3 && distance >= 10 && Math.abs(dz) <= gate;
                boolean eastWest = Math.abs(dz) <= 3 && distance >= 10 && Math.abs(dx) <= gate;
                boolean diagonalTrail = Math.abs(Math.abs(dx) - Math.abs(dz)) <= 2 && distance >= inner - 1 && distance <= outer + 1
                        && ((dx > 0 && dz < 0) || (dx < 0 && dz > 0));
                if (!centralSanctum && !innerRing && !outerRing && !northSouth && !eastWest && !diagonalTrail) continue;
                int y = plannedSurfaceY(world, x, z);
                Material material;
                int noise = Math.floorMod(x * 31 + z * 17, 11);
                if (centralSanctum) {
                    material = distance > 8.7 ? Material.POLISHED_DEEPSLATE
                            : noise <= 2 ? Material.MOSSY_COBBLESTONE
                            : noise == 3 ? Material.COBBLESTONE
                            : Material.MOSS_BLOCK;
                } else if (innerRing || outerRing) {
                    material = noise <= 2 ? Material.MOSSY_STONE_BRICKS
                            : noise == 3 ? Material.CHISELED_STONE_BRICKS
                            : Material.STONE_BRICKS;
                } else {
                    material = noise <= 2 ? Material.MOSSY_COBBLESTONE
                            : noise <= 4 ? Material.PACKED_MUD
                            : Material.MUD_BRICKS;
                }
                int blockX = x, blockZ = z;
                changes.add(() -> set(world, blockX, y, blockZ, material));
                clearPlants(world, changes, x, y + 1, z);
            }
        }
    }

    private void planBoundary(World world, List<Runnable> changes) {
        int cx = blockCenterX(), cz = blockCenterZ();
        Set<Long> planned = new HashSet<>();
        int radius = visualRadius();
        int gateHalf = 6;
        for (int degree = 0; degree < 360; degree++) {
            double angle = Math.toRadians(degree);
            int dx = (int) Math.round(Math.cos(angle) * radius);
            int dz = (int) Math.round(Math.sin(angle) * radius);
            boolean gatewayOpening = Math.abs(dx) <= gateHalf || Math.abs(dz) <= gateHalf;
            if (gatewayOpening) continue;
            int x = cx + dx, z = cz + dz;
            long key = (((long) x) << 32) ^ (z & 0xffffffffL);
            if (!planned.add(key)) continue;
            int y = plannedSurfaceY(world, x, z);
            Material wall = Math.floorMod(x + z, 7) == 0 ? Material.COBBLESTONE_WALL : Material.MOSSY_COBBLESTONE_WALL;
            changes.add(() -> set(world, x, y + 1, z, wall));
            if (Math.floorMod(x * 5 + z * 3, 4) == 0) changes.add(leaves(world, x, y + 2, z, Material.AZALEA_LEAVES));
        }
    }

    private void planGateways(World world, List<Runnable> changes) {
        int gate = gateOffset();
        planGateway(world, changes, 0, -gate, true);
        planGateway(world, changes, 0, gate, true);
        planGateway(world, changes, -gate, 0, false);
        planGateway(world, changes, gate, 0, false);
    }

    private void planGateway(World world, List<Runnable> changes, int offsetX, int offsetZ, boolean alongX) {
        int cx = blockCenterX(), cz = blockCenterZ();
        int[] side = {-7, 7};
        int beamY = Integer.MIN_VALUE;
        for (int value : side) {
            int x = cx + offsetX + (alongX ? value : 0);
            int z = cz + offsetZ + (alongX ? 0 : value);
            beamY = Math.max(beamY, plannedSurfaceY(world, x, z) + 10);
        }
        final int top = beamY;
        for (int value : side) {
            int x = cx + offsetX + (alongX ? value : 0);
            int z = cz + offsetZ + (alongX ? 0 : value);
            int ground = plannedSurfaceY(world, x, z);
            for (int y = ground + 1; y <= top; y++) {
                Material material = y == ground + 1 ? Material.CHISELED_STONE_BRICKS
                        : (y + value) % 4 == 0 ? Material.MOSSY_STONE_BRICKS
                        : y >= top - 1 ? Material.POLISHED_DEEPSLATE : Material.STONE_BRICKS;
                int fy = y;
                changes.add(() -> set(world, x, fy, z, material));
            }
            changes.add(leaves(world, x, top + 1, z, Material.FLOWERING_AZALEA_LEAVES));
        }
        for (int value = -7; value <= 7; value++) {
            int x = cx + offsetX + (alongX ? value : 0);
            int z = cz + offsetZ + (alongX ? 0 : value);
            Material material = Math.abs(value) == 7 ? Material.CHISELED_STONE_BRICKS
                    : Math.abs(value) <= 2 ? Material.POLISHED_DEEPSLATE : Material.MOSSY_STONE_BRICKS;
            changes.add(() -> set(world, x, top, z, material));
            if (Math.abs(value) <= 5 && Math.floorMod(value, 2) == 0) {
                int lx = x, lz = z;
                changes.add(() -> set(world, lx, top + 1, lz, Material.PURPLE_STAINED_GLASS));
            }
        }
        int middleX = cx + offsetX, middleZ = cz + offsetZ;
        changes.add(() -> set(world, middleX, top - 1, middleZ, Material.IRON_CHAIN));
        changes.add(() -> set(world, middleX, top - 2, middleZ, Material.LANTERN));
    }

    private void planNpcPavilions(World world, List<Runnable> changes) {
        planPavilion(world, changes, -10, 14, Material.LIME_CARPET, Material.LIME_STAINED_GLASS,
                Material.CARTOGRAPHY_TABLE, Material.BOOKSHELF);
        planPavilion(world, changes, 14, 8, Material.YELLOW_CARPET, Material.YELLOW_STAINED_GLASS,
                Material.BARREL, Material.CRAFTING_TABLE);
        planPavilion(world, changes, -14, -8, Material.RED_CARPET, Material.RED_STAINED_GLASS,
                Material.LODESTONE, Material.SMITHING_TABLE);
        planPavilion(world, changes, 8, -14, Material.LIGHT_BLUE_CARPET, Material.LIGHT_BLUE_STAINED_GLASS,
                Material.CARTOGRAPHY_TABLE, Material.FLETCHING_TABLE);
        planPavilion(world, changes, 18, -18, Material.PURPLE_CARPET, Material.PURPLE_STAINED_GLASS,
                Material.ENCHANTING_TABLE, Material.SMITHING_TABLE);
    }

    private void planPavilion(World world, List<Runnable> changes, int dx, int dz, Material accentCarpet,
                              Material accentGlass, Material workstation, Material secondary) {
        int cx = blockCenterX() + dx, cz = blockCenterZ() + dz;
        int y = plannedSurfaceY(world, cx, cz);
        for (int x = cx - 5; x <= cx + 5; x++) {
            for (int z = cz - 5; z <= cz + 5; z++) {
                double distance = Math.hypot(x - cx, z - cz);
                if (distance > 5.2) continue;
                int fx = x, fz = z, fy = plannedSurfaceY(world, x, z);
                Material floor = distance > 4.1 ? Material.SPRUCE_PLANKS
                        : Math.floorMod(x * 7 + z * 5, 7) == 0 ? Material.MOSSY_COBBLESTONE
                        : Math.floorMod(x + z, 2) == 0 ? Material.PACKED_MUD : Material.MOSS_BLOCK;
                changes.add(() -> set(world, fx, fy, fz, floor));
                clearPlants(world, changes, fx, fy + 1, fz);
                if (distance <= 2.1 && Math.floorMod(x * 11 + z * 3, 4) == 0) {
                    changes.add(placeIfAir(world, fx, fy + 1, fz, accentCarpet));
                }
            }
        }

        int towardX = Integer.compare(blockCenterX(), cx);
        int towardZ = Integer.compare(blockCenterZ(), cz);
        if (towardX == 0 && towardZ == 0) towardZ = 1;
        int sideX = towardZ;
        int sideZ = -towardX;
        int backX = -towardX;
        int backZ = -towardZ;
        int[][] posts = {
                {backX * 3 + sideX * 3, backZ * 3 + sideZ * 3},
                {backX * 3 - sideX * 3, backZ * 3 - sideZ * 3},
                {sideX * 4, sideZ * 4},
                {-sideX * 4, -sideZ * 4}
        };
        for (int[] post : posts) {
            int x = cx + post[0], z = cz + post[1];
            int postY = plannedSurfaceY(world, x, z);
            for (int py = postY + 1; py <= postY + 4; py++) {
                int fy = py;
                changes.add(log(world, x, fy, z, Material.SPRUCE_LOG, Axis.Y));
            }
            changes.add(leaves(world, x, postY + 5, z, Material.FLOWERING_AZALEA_LEAVES));
        }

        int archY = y + 5;
        for (int lateral = -3; lateral <= 3; lateral++) {
            int x = cx + backX * 3 + sideX * lateral;
            int z = cz + backZ * 3 + sideZ * lateral;
            changes.add(log(world, x, archY, z, Material.DARK_OAK_LOG,
                    Math.abs(sideX) > Math.abs(sideZ) ? Axis.X : Axis.Z));
            if (Math.abs(lateral) <= 2) {
                int fx = x, fz = z;
                changes.add(() -> set(world, fx, archY - 1, fz, accentGlass));
            }
        }
        planLeafCluster(changes, world, cx + backX * 2, y + 6, cz + backZ * 2, 3);

        int leftX = cx + sideX * 3, leftZ = cz + sideZ * 3;
        int rightX = cx - sideX * 3, rightZ = cz - sideZ * 3;
        changes.add(() -> set(world, leftX, plannedSurfaceY(world, leftX, leftZ) + 1, leftZ, workstation));
        changes.add(() -> set(world, rightX, plannedSurfaceY(world, rightX, rightZ) + 1, rightZ, secondary));
        changes.add(() -> set(world, cx + backX * 2, y + 3, cz + backZ * 2, Material.LANTERN));
        changes.add(() -> set(world, cx - backX * 2, y + 1, cz - backZ * 2, Material.LANTERN));
    }

    private void planLanternsAndGardens(World world, List<Runnable> changes) {
        int cx = blockCenterX(), cz = blockCenterZ();
        int radius = visualRadius();
        for (int degree = 0; degree < 360; degree += 45) {
            double angle = Math.toRadians(degree + 22.5);
            int x = cx + (int) Math.round(Math.cos(angle) * Math.max(22, radius - 12));
            int z = cz + (int) Math.round(Math.sin(angle) * Math.max(22, radius - 12));
            int y = plannedSurfaceY(world, x, z);
            changes.add(() -> set(world, x, y + 1, z, Material.MOSSY_COBBLESTONE_WALL));
            changes.add(() -> set(world, x, y + 2, z, Material.SPRUCE_FENCE));
            changes.add(() -> set(world, x, y + 3, z, Material.LANTERN));
        }

        Material[] flowers = {Material.DANDELION, Material.POPPY, Material.CORNFLOWER, Material.OXEYE_DAISY,
                Material.ALLIUM, Material.AZURE_BLUET, Material.PINK_PETALS, Material.BLUE_ORCHID};
        for (int degree = 0; degree < 360; degree += 9) {
            double angle = Math.toRadians(degree);
            int flowerRadius = Math.max(21, radius - 12) + Math.floorMod(degree, 9);
            int x = cx + (int) Math.round(Math.cos(angle) * flowerRadius);
            int z = cz + (int) Math.round(Math.sin(angle) * flowerRadius);
            int y = plannedSurfaceY(world, x, z);
            Material flower = flowers[(degree / 9) % flowers.length];
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
        int scan = visualRadius() + 24;
        world.getNearbyEntities(center, scan, 96, scan).stream()
                .filter(entity -> entity.getPersistentDataContainer().has(entityKey, PersistentDataType.STRING))
                .forEach(Entity::remove);
        removeLobbyMobs(world);

        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        if (registry == null) {
            plugin.getLogger().severe("Citizens não disponibilizou o registro de NPCs.");
            return;
        }
        spawnNpc(registry, world, "guide", "Eira", "§aEira • Guia", -10, 14,
                DyeColor.LIME, Material.WRITTEN_BOOK);
        spawnNpc(registry, world, "trade", "Mara", "§6Mara • Trocas", 14, 8,
                DyeColor.YELLOW, Material.EMERALD);
        spawnNpc(registry, world, "clans", "amenic", "§cBorin • Clãs", -14, -8,
                DyeColor.RED, Material.RED_BANNER);
        spawnNpc(registry, world, "wilds", "Tarin", "§bTarin • Exploração", 8, -14,
                DyeColor.LIGHT_BLUE, Material.COMPASS);
        spawnNpc(registry, world, "mods", "Nara", "§dNara • Nemeton+", 18, -18,
                DyeColor.PURPLE, Material.NETHERITE_SWORD);
        registry.saveToStore();

        spawnLabel(world, 0, Math.max(17, visualRadius() / 2), 6.0, Component.text("NEMETON\n", NamedTextColor.GOLD)
                .append(Component.text("ZONA SEGURA • sem PvP • sem grife", NamedTextColor.GREEN)), "label:welcome", 2.15f);
        spawnLabel(world, -10, 14, 3.4, Component.text("/guia  /kit  /mapa\n", NamedTextColor.GREEN)
                .append(Component.text("primeiros passos", NamedTextColor.GRAY)), "label:cmd:guide", 1.55f);
        spawnLabel(world, 14, 8, 3.4, Component.text("/troca  /comercio\n", NamedTextColor.GOLD)
                .append(Component.text("negociação segura", NamedTextColor.GRAY)), "label:cmd:trade", 1.55f);
        spawnLabel(world, -14, -8, 3.4, Component.text("/clan  /raid\n", NamedTextColor.RED)
                .append(Component.text("grupo, claims e guerras", NamedTextColor.GRAY)), "label:cmd:clans", 1.55f);
        spawnLabel(world, 8, -14, 3.4, Component.text("/santuario  /lapide  /mochila\n", NamedTextColor.AQUA)
                .append(Component.text("base pessoal e exploração", NamedTextColor.GRAY)), "label:cmd:wilds", 1.55f);
        spawnLabel(world, 18, -18, 3.4, Component.text("/mods  /mods itens\n", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text("Vanilla+ autoral e minimap", NamedTextColor.GRAY)), "label:cmd:mods", 1.55f);
        int exit = gateOffset() - 2;
        spawnExitLabel(world, 0, -exit, "NORTE");
        spawnExitLabel(world, 0, exit, "SUL");
        spawnExitLabel(world, -exit, 0, "OESTE");
        spawnExitLabel(world, exit, 0, "LESTE");
    }

    private void loadEntityChunks(World world) {
        int exit = gateOffset() - 2;
        int[][] offsets = {
                {-10, 14}, {14, 8}, {-14, -8}, {8, -14}, {18, -18},
                {0, Math.max(17, visualRadius() / 2)}, {0, -exit}, {0, exit}, {-exit, 0}, {exit, 0}
        };
        for (int[] offset : offsets) {
            int x = blockCenterX() + offset[0];
            int z = blockCenterZ() + offset[1];
            world.getChunkAt(x >> 4, z >> 4).load();
        }
    }

    private void spawnNpc(NPCRegistry registry, World world, String role, String skinName, String name,
                          int dx, int dz, DyeColor color, Material heldItem) {
        int x = blockCenterX() + dx, z = blockCenterZ() + dz;
        int y = naturalSurfaceY(world, x, z) + 1;
        Location location = new Location(world, x + 0.5, y, z + 0.5);
        world.getChunkAt(location).load();
        NPC npc = findLobbyNpc(registry, role);
        boolean created = npc == null;
        if (created) npc = registry.createNPC(EntityType.PLAYER, name);
        npc.data().setPersistent(CITIZENS_ROLE, role);
        npc.setName(name);
        npc.setAlwaysUseNameHologram(true);
        npc.setProtected(true);
        npc.setUseMinecraftAI(false);
        LookClose look = npc.getOrAddTrait(LookClose.class);
        look.lookClose(true);
        look.setRange(9.0);
        look.setRealisticLooking(true);
        look.setPerPlayer(true);
        npc.getOrAddTrait(SkinTrait.class).setSkinName(skinName, false);
        if (npc.isSpawned()) npc.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
        else if (!npc.spawn(location)) {
            plugin.getLogger().warning("Não foi possível posicionar o NPC " + name + ".");
            return;
        }
        if (npc.getEntity() instanceof LivingEntity living) equipNpc(living.getEquipment(), role, color, heldItem);
    }

    private NPC findLobbyNpc(NPCRegistry registry, String role) {
        NPC selected = null;
        List<NPC> duplicates = new ArrayList<>();
        for (NPC npc : registry) {
            if (!role.equals(npc.data().get(CITIZENS_ROLE))) continue;
            if (selected == null) selected = npc;
            else duplicates.add(npc);
        }
        duplicates.forEach(NPC::destroy);
        return selected;
    }

    private void equipNpc(EntityEquipment equipment, String role, DyeColor dyeColor, Material heldItem) {
        if (role.equals("clans")) {
            equipment.setHelmet(trimmed(Material.DIAMOND_HELMET, TrimMaterial.REDSTONE, TrimPattern.SENTRY, true));
            equipment.setChestplate(trimmed(Material.DIAMOND_CHESTPLATE, TrimMaterial.NETHERITE, TrimPattern.WARD, true));
            equipment.setLeggings(trimmed(Material.NETHERITE_LEGGINGS, TrimMaterial.DIAMOND, TrimPattern.SILENCE, true));
            equipment.setBoots(trimmed(Material.NETHERITE_BOOTS, TrimMaterial.REDSTONE, TrimPattern.DUNE, true));
            equipment.setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
            equipment.setItemInOffHand(new ItemStack(Material.SHIELD));
            return;
        }
        if (role.equals("mods")) {
            equipment.setHelmet(new ItemStack(Material.AMETHYST_SHARD));
            equipment.setChestplate(trimmed(Material.DIAMOND_CHESTPLATE, TrimMaterial.AMETHYST, TrimPattern.SPIRE, true));
            equipment.setLeggings(trimmed(Material.NETHERITE_LEGGINGS, TrimMaterial.AMETHYST, TrimPattern.EYE, true));
            equipment.setBoots(leather(Material.LEATHER_BOOTS, dyeColor.getColor()));
            equipment.setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
            equipment.setItemInOffHand(new ItemStack(Material.AMETHYST_SHARD));
            return;
        }
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

    private ItemStack trimmed(Material material, TrimMaterial trimMaterial, TrimPattern pattern, boolean glint) {
        ItemStack item = new ItemStack(material);
        if (item.getItemMeta() instanceof ArmorMeta meta) {
            meta.setTrim(new ArmorTrim(trimMaterial, pattern));
            meta.setEnchantmentGlintOverride(glint);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void spawnLabel(World world, int dx, int dz, double height, Component text, String id) {
        spawnLabel(world, dx, dz, height, text, id, 1.0f);
    }

    private void spawnLabel(World world, int dx, int dz, double height, Component text, String id, float scale) {
        int x = blockCenterX() + dx, z = blockCenterZ() + dz;
        int y = naturalSurfaceY(world, x, z);
        world.spawn(new Location(world, x + 0.5, y + height, z + 0.5), TextDisplay.class, display -> {
            display.getPersistentDataContainer().set(entityKey, PersistentDataType.STRING, id);
            display.text(text);
            display.setBillboard(Display.Billboard.CENTER);
            display.setSeeThrough(false);
            display.setShadowed(true);
            display.setViewRange(3.0f);
            display.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0),
                    new AxisAngle4f(),
                    new Vector3f(scale, scale, scale),
                    new AxisAngle4f()));
            display.setPersistent(true);
        });
    }

    private void spawnExitLabel(World world, int dx, int dz, String direction) {
        spawnLabel(world, dx, dz, 4.2,
                Component.text("SAÍDA " + direction + "\n", NamedTextColor.YELLOW)
                        .append(Component.text("além do portal: terras selvagens", NamedTextColor.GRAY)),
                "label:exit:" + direction.toLowerCase(Locale.ROOT), 2.6f);
    }

    private void faceNearbyPlayers() {
        World world = world();
        if (world == null) return;
        for (Entity entity : world.getNearbyEntities(
                new Location(world, settings.hub().centerX(), settings.hub().y(), settings.hub().centerZ()),
                visualRadius() + 8, 30, visualRadius() + 8)) {
            String role = entity.getPersistentDataContainer().get(entityKey, PersistentDataType.STRING);
            if (!(entity instanceof ArmorStand stand) || role == null || !role.startsWith("npc:")) continue;
            Player nearest = world.getNearbyPlayers(stand.getLocation(), 8).stream()
                    .min(Comparator.comparingDouble(player -> player.getLocation().distanceSquared(stand.getLocation())))
                    .orElse(null);
            if (nearest == null) continue;
            Location look = stand.getLocation();
            double dx = nearest.getX() - look.getX(), dz = nearest.getZ() - look.getZ();
            look.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            stand.teleport(look);
        }
    }

    private void removeLobbyMobs(World world) {
        int radius = visualRadius() + 4;
        BoundingBox area = new BoundingBox(settings.hub().centerX() - radius, world.getMinHeight(), settings.hub().centerZ() - radius,
                settings.hub().centerX() + radius, world.getMaxHeight(), settings.hub().centerZ() + radius);
        world.getNearbyEntities(area).forEach(entity -> {
            if (entity instanceof Player) return;
            if (entity.getPersistentDataContainer().has(entityKey, PersistentDataType.STRING)) return;
            if (!isInside(entity.getLocation())) return;
            if (entity instanceof LivingEntity || entity instanceof Projectile || entity instanceof TNTPrimed
                    || entity instanceof FallingBlock || entity instanceof AreaEffectCloud || entity instanceof EvokerFangs) entity.remove();
        });
    }

    private void clearPlants(World world, List<Runnable> changes, int x, int y, int z) {
        changes.add(() -> {
            Block block = world.getBlockAt(x, y, z);
            if (block.isPassable() && block.getType() != Material.WATER && block.getType() != Material.LAVA) {
                block.setType(Material.AIR, false);
            }
        });
    }

    private void clearColumn(World world, List<Runnable> changes, int x, int z, int minY, int maxY) {
        for (int y = minY; y <= maxY; y++) {
            int fy = y;
            changes.add(() -> set(world, x, fy, z, Material.AIR));
        }
    }

    private int plannedSurfaceY(World world, int x, int z) {
        int base = lobbyBaseY(world);
        double distance = Math.hypot(x - blockCenterX(), z - blockCenterZ());
        if (distance <= 24) return base;
        if (distance <= 34) return base + (int) Math.round((distance - 24) / 7.0);
        int natural = terrainSurfaceY(world, x, z);
        int terrace = base + 2 + (int) Math.round((distance - 34) / 5.5);
        return clamp(natural, base - 1, Math.min(base + 5, terrace));
    }

    private int lobbyBaseY(World world) {
        int configured = (int) Math.floor(settings.hub().y()) - 1;
        return clamp(configured, world.getMinHeight() + 8, world.getMaxHeight() - NEMETON_TREE_HEIGHT - 40);
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private int naturalSurfaceY(World world, int x, int z) {
        int maximum = Math.min(world.getMaxHeight() - 2, 110);
        for (int y = maximum; y >= world.getMinHeight(); y--) {
            Material material = world.getBlockAt(x, y, z).getType();
            if (isGround(material)) return y;
        }
        return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    }

    private int terrainSurfaceY(World world, int x, int z) {
        int maximum = Math.min(world.getMaxHeight() - 2, 130);
        for (int y = maximum; y >= world.getMinHeight(); y--) {
            Material material = world.getBlockAt(x, y, z).getType();
            if (isTerrainBase(material)) return y;
        }
        return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    }

    private boolean isTerrainBase(Material material) {
        return switch (material) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, ROOTED_DIRT, PODZOL, MYCELIUM, MOSS_BLOCK,
                    STONE, ANDESITE, DIORITE, GRANITE, SAND, RED_SAND, GRAVEL, CLAY,
                    DIRT_PATH, PACKED_MUD, MUD_BRICKS, COBBLESTONE, MOSSY_COBBLESTONE,
                    STONE_BRICKS, MOSSY_STONE_BRICKS, POLISHED_DEEPSLATE, SMOOTH_BASALT,
                    DEEPSLATE_BRICKS, SNOW_BLOCK -> true;
            default -> false;
        };
    }

    private boolean canBecomeMeadow(Material material) {
        return switch (material) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, ROOTED_DIRT, PODZOL, MYCELIUM, SNOW_BLOCK,
                    STONE, ANDESITE, DIORITE, GRANITE, PACKED_MUD, MUD_BRICKS -> true;
            default -> false;
        };
    }

    private boolean shouldClearLobbySculpture(Material material) {
        if (material.isAir() || material == Material.BEDROCK) return false;
        return true;
    }

    private boolean shouldClearLobbyDecoration(Material material) {
        if (material.isAir() || material == Material.WATER || material == Material.LAVA) return false;
        String name = material.name();
        if (name.endsWith("_LEAVES") || name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_FENCE") || name.endsWith("_FENCE_GATE") || name.endsWith("_WALL")
                || name.endsWith("_WOOL") || name.endsWith("_CARPET") || name.endsWith("_BANNER")
                || name.endsWith("_SAPLING") || name.endsWith("_STAINED_GLASS")) return true;
        return switch (material) {
            case BEACON, IRON_BLOCK, GOLD_BLOCK, EMERALD_BLOCK, DIAMOND_BLOCK,
                    LANTERN, IRON_CHAIN, TORCH, SOUL_TORCH,
                    CARTOGRAPHY_TABLE, CRAFTING_TABLE, FLETCHING_TABLE, SMITHING_TABLE,
                    BARREL, CHEST, LODESTONE, BOOKSHELF,
                    SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN, VINE, GLOW_LICHEN,
                    DANDELION, POPPY, CORNFLOWER, OXEYE_DAISY, ALLIUM, AZURE_BLUET,
                    FLOWERING_AZALEA, AZALEA, DEAD_BUSH, MOSS_CARPET -> true;
            default -> false;
        };
    }

    private void activateBeacon(World world) {
        int cx = blockCenterX(), cz = blockCenterZ();
        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
            Block block = world.getBlockAt(cx, y, cz);
            if (block.getType() != Material.BEACON) continue;
            for (int clearY = y + 1; clearY < world.getMaxHeight(); clearY++) {
                Block above = world.getBlockAt(cx, clearY, cz);
                if (!above.getType().isAir() && !isBeaconBeamTransparent(above.getType())) above.setType(Material.AIR, false);
            }
            if (block.getState() instanceof Beacon beacon) {
                beacon.setPrimaryEffect(PotionEffectType.SPEED);
                beacon.setSecondaryEffect(PotionEffectType.REGENERATION);
                beacon.update(true, true);
            }
            return;
        }
    }

    private boolean isBeaconBeamTransparent(Material material) {
        return material == Material.GLASS || material.name().endsWith("_STAINED_GLASS");
    }

    private boolean isGround(Material material) {
        return switch (material) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, ROOTED_DIRT, PODZOL, MYCELIUM, MOSS_BLOCK,
                    STONE, ANDESITE, DIORITE, GRANITE, SAND, RED_SAND, GRAVEL, CLAY,
                    DIRT_PATH, PACKED_MUD, MUD_BRICKS, COBBLESTONE, MOSSY_COBBLESTONE,
                    STONE_BRICKS, MOSSY_STONE_BRICKS, POLISHED_DEEPSLATE, SMOOTH_BASALT,
                    DEEPSLATE_BRICKS, SPRUCE_PLANKS -> true;
            default -> false;
        };
    }

    private void set(World world, int x, int y, int z, Material material) {
        world.getBlockAt(x, y, z).setType(material, false);
    }

    private Runnable log(World world, int x, int y, int z, Material material, Axis axis) {
        return () -> {
            BlockData data = Bukkit.createBlockData(material);
            if (data instanceof Orientable orientable) orientable.setAxis(axis);
            world.getBlockAt(x, y, z).setBlockData(data, false);
        };
    }

    private Runnable leaves(World world, int x, int y, int z, Material material) {
        return () -> {
            Block block = world.getBlockAt(x, y, z);
            if (!block.getType().isAir() && !block.getType().name().endsWith("_LEAVES")) return;
            BlockData data = Bukkit.createBlockData(material);
            if (data instanceof Leaves leaves) leaves.setPersistent(true);
            block.setBlockData(data, false);
        };
    }

    private Runnable placeIfAir(World world, int x, int y, int z, Material material) {
        return () -> {
            Block block = world.getBlockAt(x, y, z);
            if (block.getType().isAir()) block.setType(material, false);
        };
    }

    private World world() { return Bukkit.getWorld(settings.hub().world()); }
    private int blockCenterX() { return (int) Math.floor(settings.hub().centerX()); }
    private int blockCenterZ() { return (int) Math.floor(settings.hub().centerZ()); }
    private int beaconY() { return (int) Math.floor(settings.hub().y()); }
    private int visualRadius() { return Math.max(MIN_VISUAL_RADIUS, Math.min(settings.hub().radius(), MAX_VISUAL_RADIUS)); }
    private int gateOffset() { return Math.max(visualRadius() - 3, 26); }
    private double centered(double coordinate) { return Math.floor(coordinate) + 0.5; }
}
