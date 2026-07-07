package dev.nemeton.service;

import dev.nemeton.integration.BedrockForms;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** One craftable, personal 27-slot backpack backed by server data and compatible with Geyser. */
public final class BackpackService implements Listener, TabExecutor {
    private static final int SIZE = 27;

    private final JavaPlugin plugin;
    private final NamespacedKey tokenKey;
    private final NamespacedKey ownerKey;
    private final File file;
    private final YamlConfiguration data;

    public BackpackService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.tokenKey = new NamespacedKey(plugin, "backpack_token");
        this.ownerKey = new NamespacedKey(plugin, "backpack_owner");
        this.file = new File(plugin.getDataFolder(), "backpacks.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        registerRecipe();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Comando disponível apenas em jogo.");
            return true;
        }
        ItemStack token = findOwnedToken(player);
        if (token == null) {
            if (BedrockForms.sendSimple(plugin, player, "Mochila",
                    "Você ainda não tem mochila.\n\nReceita:\nLinha no topo, couro em volta e baú no centro.\n\nEla é pessoal, tem 27 espaços e continua guardada após a morte.",
                    ignored -> {},
                    "Entendi")) {
                return true;
            }
            player.sendMessage("§eVocê ainda não tem mochila. Receita: couro em volta de um baú, com uma linha no topo.");
            player.sendMessage("§7A mochila é pessoal, tem 27 espaços e continua guardada mesmo após sua morte.");
            return true;
        }
        open(player);
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { return List.of(); }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !isBackpack(event.getCurrentItem())) return;
        if (event.isShiftClick()) {
            event.setCancelled(true);
            player.sendMessage("§eCrie a mochila com um clique normal para vinculá-la corretamente.");
            return;
        }
        ItemStack result = event.getCurrentItem();
        if (result == null) return;
        bind(result, player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || !isBackpack(event.getItem())) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!owns(event.getItem(), player)) {
            player.sendMessage("§cEssa mochila pertence a outro jogador.");
            return;
        }
        open(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BackpackHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner().equals(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        if ((event.getClickedInventory() == event.getView().getTopInventory() && isBackpack(cursor))
                || (event.isShiftClick() && event.getClickedInventory() == player.getInventory() && isBackpack(current))) {
            event.setCancelled(true);
            player.sendMessage("§cUma mochila não pode ser guardada dentro dela mesma.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BackpackHolder)) return;
        if (!isBackpack(event.getOldCursor())) return;
        if (event.getRawSlots().stream().anyMatch(slot -> slot < SIZE)) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof BackpackHolder holder)) return;
        save(holder.owner(), event.getInventory().getContents());
    }

    public void shutdown() {
        Bukkit.getOnlinePlayers().forEach(player -> {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof BackpackHolder holder) {
                save(holder.owner(), player.getOpenInventory().getTopInventory().getContents());
            }
        });
    }

    private void open(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof BackpackHolder) return;
        BackpackHolder holder = new BackpackHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, SIZE, Component.text("Mochila de " + player.getName()));
        holder.inventory = inventory;
        List<?> stored = data.getList("backpacks." + player.getUniqueId(), List.of());
        for (int slot = 0; slot < Math.min(SIZE, stored.size()); slot++) {
            if (stored.get(slot) instanceof ItemStack item) inventory.setItem(slot, item.clone());
        }
        player.openInventory(inventory);
        player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_INSERT, 0.7f, 1.0f);
    }

    private void save(UUID owner, ItemStack[] contents) {
        List<ItemStack> items = new ArrayList<>(SIZE);
        for (int slot = 0; slot < SIZE; slot++) items.add(contents[slot] == null ? null : contents[slot].clone());
        data.set("backpacks." + owner, items);
        try { data.save(file); }
        catch (IOException exception) { plugin.getLogger().severe("Falha ao salvar mochila de " + owner + ": " + exception.getMessage()); }
    }

    private void registerRecipe() {
        ItemStack result = new ItemStack(Material.BUNDLE);
        ItemMeta meta = result.getItemMeta();
        meta.displayName(Component.text("Mochila sem dono"));
        meta.lore(List.of(Component.text("Será vinculada a quem fabricar."), Component.text("Clique direito ou use /mochila.")));
        meta.setCustomModelData(7110);
        meta.setItemModel(new NamespacedKey("nemeton", "backpack"));
        result.setItemMeta(meta);
        result.editPersistentDataContainer(container -> container.set(tokenKey, PersistentDataType.BYTE, (byte) 1));

        ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(plugin, "personal_backpack"), result);
        recipe.shape("LSL", "LCL", "LLL");
        recipe.setIngredient('L', Material.LEATHER);
        recipe.setIngredient('S', Material.STRING);
        recipe.setIngredient('C', Material.CHEST);
        Bukkit.addRecipe(recipe, true);
    }

    private void bind(ItemStack item, Player player) {
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Mochila de " + player.getName()));
        meta.lore(List.of(Component.text("27 espaços pessoais."), Component.text("Clique direito ou use /mochila.")));
        meta.setCustomModelData(7110);
        meta.setItemModel(new NamespacedKey("nemeton", "backpack"));
        item.setItemMeta(meta);
        item.editPersistentDataContainer(container -> {
            container.set(tokenKey, PersistentDataType.BYTE, (byte) 1);
            container.set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        });
    }

    private ItemStack findOwnedToken(Player player) {
        return Arrays.stream(player.getInventory().getContents())
                .filter(Objects::nonNull).filter(this::isBackpack).filter(item -> owns(item, player)).findFirst().orElse(null);
    }

    private boolean isBackpack(ItemStack item) {
        return item != null && item.getPersistentDataContainer().has(tokenKey, PersistentDataType.BYTE);
    }

    private boolean owns(ItemStack item, Player player) {
        if (item == null) return false;
        String owner = item.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return player.getUniqueId().toString().equals(owner);
    }

    private static final class BackpackHolder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;
        private BackpackHolder(UUID owner) { this.owner = owner; }
        private UUID owner() { return owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
