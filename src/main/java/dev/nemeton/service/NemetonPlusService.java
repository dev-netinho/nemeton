package dev.nemeton.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/** Vanilla+ authored content: custom drops, boss rewards, recipes and mod guidance. */
public final class NemetonPlusService implements Listener, TabExecutor {
    private static final Set<Material> ESSENCE_ORES = EnumSet.of(
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE);

    private final JavaPlugin plugin;
    private final NamespacedKey itemKey;
    private final Random random = new Random();

    public NemetonPlusService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "plus_item");
        registerRecipes();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Comando disponível apenas em jogo.");
            return true;
        }
        String sub = args.length == 0 ? "guia" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "itens", "items", "receitas" -> sendItems(player);
            case "mods", "guia", "ajuda" -> sendGuide(player);
            case "give", "pegar" -> giveShowcase(player);
            default -> {
                player.sendMessage("§cUse /mods, /mods itens ou /mods give.");
                return true;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("guia", "itens", "give").stream()
                .filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMine(BlockBreakEvent event) {
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) return;
        Material type = event.getBlock().getType();
        if (!ESSENCE_ORES.contains(type)) return;
        double chance = switch (type) {
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE, EMERALD_ORE, DEEPSLATE_EMERALD_ORE -> 0.055;
            case GOLD_ORE, DEEPSLATE_GOLD_ORE, LAPIS_ORE, DEEPSLATE_LAPIS_ORE -> 0.035;
            default -> 0.022;
        };
        if (random.nextDouble() > chance) return;
        event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), item("root_essence", 1));
        event.getPlayer().sendActionBar(Component.text("✦ Você encontrou uma Essência do Nemeton.", NamedTextColor.LIGHT_PURPLE));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBossDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Wither) {
            event.getDrops().add(item("abyss_heart", 1));
            event.getDrops().add(item("root_essence", 8));
            Bukkit.broadcast(Component.text("✦ O Wither deixou um Coração Abissal para a comunidade.", NamedTextColor.DARK_PURPLE));
        } else if (event.getEntity() instanceof EnderDragon) {
            event.getDrops().add(item("end_heart", 1));
            event.getDrops().add(item("root_essence", 12));
            Bukkit.broadcast(Component.text("✦ O Dragão deixou um Coração do Fim para a comunidade.", NamedTextColor.LIGHT_PURPLE));
        }
    }

    private void registerRecipes() {
        register(new NamespacedKey(plugin, "root_blade"), sword(), recipe -> {
            recipe.shape(" E ", " D ", " S ");
            recipe.setIngredient('E', new RecipeChoice.ExactChoice(item("root_essence", 1)));
            recipe.setIngredient('D', Material.DIAMOND);
            recipe.setIngredient('S', Material.STICK);
        });
        register(new NamespacedKey(plugin, "warden_axe"), axe(), recipe -> {
            recipe.shape("EE ", "ES ", " S ");
            recipe.setIngredient('E', new RecipeChoice.ExactChoice(item("root_essence", 1)));
            recipe.setIngredient('S', Material.STICK);
        });
        register(new NamespacedKey(plugin, "sentinel_chestplate"), chestplate(), recipe -> {
            recipe.shape(" E ", "ECE", " E ");
            recipe.setIngredient('E', new RecipeChoice.ExactChoice(item("root_essence", 1)));
            recipe.setIngredient('C', Material.DIAMOND_CHESTPLATE);
        });
    }

    private void register(NamespacedKey key, ItemStack result, java.util.function.Consumer<ShapedRecipe> setup) {
        Bukkit.removeRecipe(key);
        ShapedRecipe recipe = new ShapedRecipe(key, result);
        setup.accept(recipe);
        Bukkit.addRecipe(recipe, true);
    }

    private void sendGuide(Player player) {
        player.sendMessage("§d§lNemeton+ Alpha");
        player.sendMessage("§7O servidor continua crossplay, então mods pesados de Forge/Fabric não entram no servidor principal.");
        player.sendMessage("§7Aqui vamos usar plugin próprio, datapacks, resource packs e mods opcionais por cliente.");
        player.sendMessage("§fJava/Lunar: §7ative o minimap no Lunar: Right Shift → Mods → Minimap.");
        player.sendMessage("§fBedrock: §7use §f/mapa§7 e o mapa web. Addons Bedrock não rodam como Forge em servidor Java.");
        player.sendMessage(Component.text("§bAbrir mapa ao vivo")
                .clickEvent(ClickEvent.openUrl("https://expected-collaborative-tide-naval.trycloudflare.com/?world=minecraft_overworld&x=16064&z=-32064&zoom=3")));
        player.sendMessage("§7Use §f/mods itens§7 para ver os itens autorais já ativos.");
    }

    private void sendItems(Player player) {
        player.sendMessage("§d§lItens Nemeton+");
        player.sendMessage("§fEssência do Nemeton §7— chance ao minerar minérios; maior em diamante/esmeralda.");
        player.sendMessage("§fLâmina do Nemeton §7— essência + diamante + graveto.");
        player.sendMessage("§fMachado do Guardião §7— duas essências + graveto.");
        player.sendMessage("§fPeitoral Sentinela §7— peitoral de diamante cercado por essências.");
        player.sendMessage("§fWither/Dragon §7— deixam corações especiais para eventos comunitários.");
    }

    private void giveShowcase(Player player) {
        if (!player.hasPermission("nemeton.admin")) {
            player.sendMessage("§cApenas administração pode gerar vitrine de itens.");
            return;
        }
        give(player, item("root_essence", 16));
        give(player, sword());
        give(player, axe());
        give(player, chestplate());
        give(player, item("abyss_heart", 1));
        give(player, item("end_heart", 1));
        player.sendMessage("§aVitrine Nemeton+ entregue.");
    }

    private void give(Player player, ItemStack item) {
        player.getInventory().addItem(item).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    private ItemStack sword() {
        ItemStack item = tagged(Material.DIAMOND_SWORD, "root_blade", "§dLâmina do Nemeton",
                "§7Forjada com essência encontrada nas profundezas.",
                "§8Nemeton+ Alpha");
        enchant(item, "sharpness", 3);
        enchant(item, "unbreaking", 2);
        return item;
    }

    private ItemStack axe() {
        ItemStack item = tagged(Material.DIAMOND_AXE, "warden_axe", "§5Machado do Guardião",
                "§7Uma arma pesada para proteger a clareira.",
                "§8Nemeton+ Alpha");
        enchant(item, "efficiency", 2);
        enchant(item, "unbreaking", 2);
        return item;
    }

    private ItemStack chestplate() {
        ItemStack item = tagged(Material.DIAMOND_CHESTPLATE, "sentinel_chestplate", "§aPeitoral Sentinela",
                "§7Armadura cerimonial dos defensores do Nemeton.",
                "§8Visual completo virá no resource pack próprio.");
        enchant(item, "protection", 3);
        enchant(item, "unbreaking", 2);
        return item;
    }

    private ItemStack item(String id, int amount) {
        return switch (id) {
            case "root_essence" -> tagged(Material.AMETHYST_SHARD, id, amount, "§dEssência do Nemeton",
                    "§7Pulsa como uma raiz antiga.", "§8Drop raro de mineração.");
            case "abyss_heart" -> tagged(Material.NETHER_STAR, id, amount, "§5Coração Abissal",
                    "§7Troféu de evento contra o Wither.", "§8Use em futuras forjas do clã.");
            case "end_heart" -> tagged(Material.DRAGON_BREATH, id, amount, "§dCoração do Fim",
                    "§7Troféu de evento contra o Dragão.", "§8Use em futuras forjas do clã.");
            default -> throw new IllegalArgumentException("Item Nemeton+ desconhecido: " + id);
        };
    }

    private ItemStack tagged(Material material, String id, String name, String... lore) {
        return tagged(material, id, 1, name, lore);
    }

    private ItemStack tagged(Material material, String id, int amount, String name, String... lore) {
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        meta.lore(Arrays.stream(lore).map(Component::text).toList());
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    @SuppressWarnings("deprecation")
    private void enchant(ItemStack item, String key, int level) {
        Enchantment enchantment = Enchantment.getByKey(NamespacedKey.minecraft(key));
        if (enchantment == null) return;
        item.addUnsafeEnchantment(enchantment, level);
    }
}
