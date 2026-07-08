package dev.nemeton.service;

import dev.nemeton.integration.BedrockForms;
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
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.*;

/** Vanilla+ authored content: custom drops, boss rewards, recipes and mod guidance. */
public final class NemetonPlusService implements Listener, TabExecutor {
    private static final String ROOT_ESSENCE = "root_essence";
    private static final String ROOT_BLADE = "root_blade";
    private static final String WARDEN_AXE = "warden_axe";
    private static final String SENTINEL_CHESTPLATE = "sentinel_chestplate";
    private static final String ABYSS_HEART = "abyss_heart";
    private static final String END_HEART = "end_heart";

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
            case "restaurar", "corrigir" -> {
                int fixed = repairInventory(player);
                player.sendMessage(fixed == 0
                        ? "§eNenhum item Nemeton+ antigo foi encontrado no seu inventário."
                        : "§a" + fixed + " item(ns) Nemeton+ foram restaurados.");
            }
            case "give", "pegar" -> giveShowcase(player);
            default -> {
                player.sendMessage("§cUse /mods, /mods itens, /mods restaurar ou /mods give.");
                return true;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("guia", "itens", "restaurar", "give").stream()
                .filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMine(BlockBreakEvent event) {
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) return;
        Material type = event.getBlock().getType();
        if (!ESSENCE_ORES.contains(type)) return;
        double chance = switch (type) {
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE, EMERALD_ORE, DEEPSLATE_EMERALD_ORE -> 0.085;
            case GOLD_ORE, DEEPSLATE_GOLD_ORE, LAPIS_ORE, DEEPSLATE_LAPIS_ORE -> 0.048;
            case IRON_ORE, DEEPSLATE_IRON_ORE, REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE -> 0.028;
            default -> 0.018;
        };
        if (random.nextDouble() > chance) return;
        event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), item(ROOT_ESSENCE, 1));
        event.getPlayer().sendActionBar(Component.text("✦ Você encontrou uma Essência do Nemeton.", NamedTextColor.LIGHT_PURPLE));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBossDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Wither) {
            event.getDrops().add(item(ABYSS_HEART, 1));
            event.getDrops().add(item(ROOT_ESSENCE, 16));
            Bukkit.broadcast(Component.text("✦ O Wither deixou um Coração Abissal e 16 Essências do Nemeton.", NamedTextColor.DARK_PURPLE));
        } else if (event.getEntity() instanceof EnderDragon) {
            event.getDrops().add(item(END_HEART, 1));
            event.getDrops().add(item(ROOT_ESSENCE, 24));
            Bukkit.broadcast(Component.text("✦ O Dragão deixou um Coração do Fim e 24 Essências do Nemeton.", NamedTextColor.LIGHT_PURPLE));
        }
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        String id = plusId(result);
        if (id == null) return;
        if (!matchesRecipe(id, event.getInventory().getMatrix())) {
            event.getInventory().setResult(new ItemStack(Material.AIR));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        String id = plusId(event.getCurrentItem());
        if (id == null) return;
        if (!matchesRecipe(id, event.getInventory().getMatrix())) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                player.sendMessage("§cUse Essências/Corações do Nemeton verdadeiros. Ametista comum não serve na forja.");
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> repairInventory(event.getPlayer()), 40L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> repairInventory(event.getPlayer()), 40L);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            Bukkit.getScheduler().runTask(plugin, () -> repairInventory(player));
        }
    }

    private void registerRecipes() {
        register(new NamespacedKey(plugin, "root_blade"), sword(), recipe -> {
            recipe.shape(" E ", "ESE", " E ");
            recipe.setIngredient('E', Material.AMETHYST_SHARD);
            recipe.setIngredient('S', Material.DIAMOND_SWORD);
        });
        register(new NamespacedKey(plugin, "warden_axe"), axe(), recipe -> {
            recipe.shape(" H ", "EAE", " E ");
            recipe.setIngredient('H', Material.NETHER_STAR);
            recipe.setIngredient('E', Material.AMETHYST_SHARD);
            recipe.setIngredient('A', Material.DIAMOND_AXE);
        });
        register(new NamespacedKey(plugin, "sentinel_chestplate"), chestplate(), recipe -> {
            recipe.shape("EHE", "ECE", "EEE");
            recipe.setIngredient('H', Material.DRAGON_BREATH);
            recipe.setIngredient('E', Material.AMETHYST_SHARD);
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
        if (BedrockForms.sendSimple(plugin, player,
                "Nemeton+ Alpha",
                "O servidor continua crossplay: nada de modpack obrigatório.\n\n"
                        + "Usamos plugin próprio, datapacks, itens com visual vanilla-safe e, depois dos testes, packs Java/Bedrock equivalentes.\n\n"
                        + "Essências do Nemeton caem raramente ao minerar minérios. Elas viraram material de forja: espada boa de midgame e relíquias de boss.\n\n"
                        + "Java/Lunar: ative o minimap no cliente.\n"
                        + "Bedrock: use /mapa, telas nativas e o mapa ao vivo.",
                index -> {
                    if (index == 0) sendItems(player);
                    else if (index == 1) player.performCommand("mapa");
                    else if (index == 2 && player.hasPermission("nemeton.admin")) giveShowcase(player);
                },
                player.hasPermission("nemeton.admin")
                        ? new String[]{"Ver itens", "Abrir /mapa", "Receber vitrine admin", "Fechar"}
                        : new String[]{"Ver itens", "Abrir /mapa", "Fechar"})) {
            return;
        }
        player.sendMessage("§d§lNemeton+ Alpha");
        player.sendMessage("§7O servidor continua crossplay, então mods pesados de Forge/Fabric não entram no servidor principal.");
        player.sendMessage("§7Aqui vamos usar plugin próprio, datapacks, resource packs e mods opcionais por cliente.");
        player.sendMessage("§fEssências: §7drop raro ao minerar minérios; diamante/esmeralda têm a melhor chance.");
        player.sendMessage("§fForja: §7espada usa essências; machado e peitoral usam corações de boss.");
        player.sendMessage("§fJava/Lunar: §7ative o minimap no Lunar: Right Shift → Mods → Minimap.");
        player.sendMessage("§fBedrock: §7use §f/mapa§7 e o mapa web. Addons Bedrock não rodam como Forge em servidor Java.");
        player.sendMessage(Component.text("§bAbrir mapa ao vivo")
                .clickEvent(ClickEvent.openUrl("https://expected-collaborative-tide-naval.trycloudflare.com/?world=minecraft_overworld&x=16064&z=-32064&zoom=3")));
        player.sendMessage("§7Use §f/mods itens§7 para receitas. Se item antigo virar ametista visualmente, use §f/mods restaurar§7.");
    }

    private void sendItems(Player player) {
        if (BedrockForms.sendSimple(plugin, player,
                "Itens Nemeton+",
                "Essência do Nemeton\n"
                        + "• Drop raro ao minerar minérios.\n"
                        + "• Chance baixa em cobre/ferro/redstone.\n"
                        + "• Chance melhor em ouro/lápis.\n"
                        + "• Melhor chance em diamante/esmeralda.\n\n"
                        + "Receitas\n"
                        + "• Lâmina: espada de diamante + 4 essências.\n"
                        + "• Machado: machado de diamante + Coração Abissal + 3 essências.\n"
                        + "• Peitoral: peitoral de diamante + Coração do Fim + 7 essências.\n\n"
                        + "Eventos\n"
                        + "• Wither: Coração Abissal + 16 essências.\n"
                        + "• Dragon: Coração do Fim + 24 essências.\n\n"
                        + "Correção\n"
                        + "• /mods restaurar repara itens antigos que perderam visual/meta.",
                index -> {
                    if (index == 0) sendGuide(player);
                    else if (index == 1 && player.hasPermission("nemeton.admin")) giveShowcase(player);
                },
                player.hasPermission("nemeton.admin")
                        ? new String[]{"Voltar", "Receber vitrine admin", "Fechar"}
                        : new String[]{"Voltar", "Fechar"})) {
            return;
        }
        player.sendMessage("§d§lItens Nemeton+");
        player.sendMessage("§fEssência do Nemeton §7— chance rara ao minerar minérios; maior em diamante/esmeralda.");
        player.sendMessage("§fLâmina do Nemeton §7— espada de diamante + 4 essências; Sharpness IV, Looting II e durabilidade real.");
        player.sendMessage("§fMachado do Guardião §7— machado de diamante + Coração Abissal + 3 essências; peça de Wither.");
        player.sendMessage("§fPeitoral Sentinela §7— peitoral de diamante + Coração do Fim + 7 essências; peça de temporada.");
        player.sendMessage("§fWither/Dragon §7— deixam corações especiais e essências para eventos comunitários.");
        player.sendMessage("§7Se uma essência antiga parecer ametista comum, use §f/mods restaurar§7.");
    }

    private void giveShowcase(Player player) {
        if (!player.hasPermission("nemeton.admin")) {
            player.sendMessage("§cApenas administração pode gerar vitrine de itens.");
            return;
        }
        give(player, item(ROOT_ESSENCE, 16));
        give(player, sword());
        give(player, axe());
        give(player, chestplate());
        give(player, item(ABYSS_HEART, 1));
        give(player, item(END_HEART, 1));
        player.sendMessage("§aVitrine Nemeton+ entregue.");
    }

    private void give(Player player, ItemStack item) {
        player.getInventory().addItem(item).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    private ItemStack sword() {
        ItemStack item = tagged(Material.DIAMOND_SWORD, ROOT_BLADE, "§dLâmina do Nemeton",
                "§7Forjada com essência encontrada nas profundezas.",
                "§8Receita: espada de diamante + 4 essências.");
        enchant(item, "sharpness", 4);
        enchant(item, "looting", 2);
        enchant(item, "sweeping_edge", 3);
        enchant(item, "unbreaking", 3);
        return item;
    }

    private ItemStack axe() {
        ItemStack item = tagged(Material.DIAMOND_AXE, WARDEN_AXE, "§5Machado do Guardião",
                "§7Uma arma pesada nascida de um Coração Abissal.",
                "§8Receita: machado de diamante + Wither + essências.");
        enchant(item, "sharpness", 4);
        enchant(item, "efficiency", 4);
        enchant(item, "unbreaking", 3);
        return item;
    }

    private ItemStack chestplate() {
        ItemStack item = tagged(Material.DIAMOND_CHESTPLATE, SENTINEL_CHESTPLATE, "§aPeitoral Sentinela",
                "§7Armadura cerimonial marcada pelo Coração do Fim.",
                "§8Receita: peitoral de diamante + Dragon + essências.");
        applyTrim(item, TrimMaterial.AMETHYST, TrimPattern.WARD);
        enchant(item, "protection", 4);
        enchant(item, "thorns", 2);
        enchant(item, "unbreaking", 3);
        enchant(item, "mending", 1);
        return item;
    }

    private ItemStack item(String id, int amount) {
        return switch (id) {
            case ROOT_ESSENCE -> tagged(Material.AMETHYST_SHARD, id, amount, "§dEssência do Nemeton",
                    "§7Pulsa como uma raiz antiga.", "§8Forja itens autorais; ametista comum não serve.");
            case ABYSS_HEART -> tagged(Material.NETHER_STAR, id, amount, "§5Coração Abissal",
                    "§7Troféu de evento contra o Wither.", "§8Ingrediente do Machado do Guardião.");
            case END_HEART -> tagged(Material.DRAGON_BREATH, id, amount, "§dCoração do Fim",
                    "§7Troféu de evento contra o Dragão.", "§8Ingrediente do Peitoral Sentinela.");
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
        meta.setCustomModelData(modelId(id));
        meta.setItemModel(new NamespacedKey("nemeton", id));
        meta.setEnchantmentGlintOverride(true);
        meta.setRarity(id.contains("heart") ? ItemRarity.EPIC : ItemRarity.RARE);
        item.setItemMeta(meta);
        return item;
    }

    private boolean matchesRecipe(String id, ItemStack[] matrix) {
        if (matrix == null || matrix.length < 9) return false;
        return switch (id) {
            case ROOT_BLADE -> isAir(matrix[0]) && isEssence(matrix[1]) && isAir(matrix[2])
                    && isEssence(matrix[3]) && isMaterial(matrix[4], Material.DIAMOND_SWORD) && isEssence(matrix[5])
                    && isAir(matrix[6]) && isEssence(matrix[7]) && isAir(matrix[8]);
            case WARDEN_AXE -> isAir(matrix[0]) && isPlusItem(matrix[1], ABYSS_HEART) && isAir(matrix[2])
                    && isEssence(matrix[3]) && isMaterial(matrix[4], Material.DIAMOND_AXE) && isEssence(matrix[5])
                    && isAir(matrix[6]) && isEssence(matrix[7]) && isAir(matrix[8]);
            case SENTINEL_CHESTPLATE -> isEssence(matrix[0]) && isPlusItem(matrix[1], END_HEART) && isEssence(matrix[2])
                    && isEssence(matrix[3]) && isMaterial(matrix[4], Material.DIAMOND_CHESTPLATE) && isEssence(matrix[5])
                    && isEssence(matrix[6]) && isEssence(matrix[7]) && isEssence(matrix[8]);
            default -> true;
        };
    }

    private boolean isEssence(ItemStack item) {
        return isPlusItem(item, ROOT_ESSENCE);
    }

    private boolean isPlusItem(ItemStack item, String expected) {
        String id = plusId(item);
        return expected.equals(id);
    }

    private boolean isMaterial(ItemStack item, Material material) {
        return item != null && item.getType() == material;
    }

    private boolean isAir(ItemStack item) {
        return item == null || item.getType() == Material.AIR;
    }

    private int repairInventory(Player player) {
        int fixed = 0;
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            ItemStack repaired = repaired(item);
            if (repaired == null) continue;
            inventory.setItem(slot, repaired);
            fixed++;
        }
        return fixed;
    }

    private ItemStack repaired(ItemStack original) {
        String id = plusId(original);
        if (id == null) return null;
        ItemStack replacement = switch (id) {
            case ROOT_ESSENCE, ABYSS_HEART, END_HEART -> item(id, original.getAmount());
            case ROOT_BLADE -> sword();
            case WARDEN_AXE -> axe();
            case SENTINEL_CHESTPLATE -> chestplate();
            default -> null;
        };
        if (replacement == null || similarPlus(original, replacement)) return null;
        copyDamage(original, replacement);
        replacement.setAmount(Math.max(1, Math.min(original.getAmount(), replacement.getMaxStackSize())));
        return replacement;
    }

    private boolean similarPlus(ItemStack left, ItemStack right) {
        if (left == null || right == null || left.getType() != right.getType()) return false;
        ItemStack normalizedLeft = left.clone();
        ItemStack normalizedRight = right.clone();
        clearDamage(normalizedLeft);
        clearDamage(normalizedRight);
        return normalizedLeft.isSimilar(normalizedRight);
    }

    private void copyDamage(ItemStack original, ItemStack replacement) {
        if (original.getItemMeta() instanceof Damageable oldMeta && replacement.getItemMeta() instanceof Damageable newMeta) {
            newMeta.setDamage(oldMeta.getDamage());
            replacement.setItemMeta((ItemMeta) newMeta);
        }
    }

    private void clearDamage(ItemStack item) {
        if (item.getItemMeta() instanceof Damageable meta) {
            meta.setDamage(0);
            item.setItemMeta((ItemMeta) meta);
        }
    }

    private String plusId(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        String tagged = meta.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        if (tagged != null) return tagged;
        if (meta.hasCustomModelData()) {
            return switch (meta.getCustomModelData()) {
                case 7101 -> ROOT_ESSENCE;
                case 7102 -> ROOT_BLADE;
                case 7103 -> WARDEN_AXE;
                case 7104 -> SENTINEL_CHESTPLATE;
                case 7105 -> ABYSS_HEART;
                case 7106 -> END_HEART;
                default -> null;
            };
        }
        if (meta.displayName() == null) return null;
        String name = PlainTextComponentSerializer.plainText().serialize(meta.displayName()).toLowerCase(Locale.ROOT);
        if (name.contains("essência do nemeton") || name.contains("essencia do nemeton")) return ROOT_ESSENCE;
        if (name.contains("lâmina do nemeton") || name.contains("lamina do nemeton")) return ROOT_BLADE;
        if (name.contains("machado do guardião") || name.contains("machado do guardiao")) return WARDEN_AXE;
        if (name.contains("peitoral sentinela")) return SENTINEL_CHESTPLATE;
        if (name.contains("coração abissal") || name.contains("coracao abissal")) return ABYSS_HEART;
        if (name.contains("coração do fim") || name.contains("coracao do fim")) return END_HEART;
        return null;
    }

    private int modelId(String id) {
        return switch (id) {
            case ROOT_ESSENCE -> 7101;
            case ROOT_BLADE -> 7102;
            case WARDEN_AXE -> 7103;
            case SENTINEL_CHESTPLATE -> 7104;
            case ABYSS_HEART -> 7105;
            case END_HEART -> 7106;
            default -> 7199;
        };
    }

    private void applyTrim(ItemStack item, TrimMaterial material, TrimPattern pattern) {
        if (item.getItemMeta() instanceof ArmorMeta meta) {
            meta.setTrim(new ArmorTrim(material, pattern));
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
    }

    @SuppressWarnings("deprecation")
    private void enchant(ItemStack item, String key, int level) {
        Enchantment enchantment = Enchantment.getByKey(NamespacedKey.minecraft(key));
        if (enchantment == null) return;
        item.addUnsafeEnchantment(enchantment, level);
    }
}
