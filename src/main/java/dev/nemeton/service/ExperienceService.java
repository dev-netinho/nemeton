package dev.nemeton.service;

import dev.nemeton.config.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class ExperienceService {
    private final JavaPlugin plugin;
    private final Settings settings;
    private final NamespacedKey starterKitKey;
    private final NamespacedKey lascadoKitKey;

    public ExperienceService(JavaPlugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.starterKitKey = new NamespacedKey(plugin, "starter_kit_claimed");
        this.lascadoKitKey = new NamespacedKey(plugin, "lascado_kit_claimed");
    }

    public void welcome(Player player) {
        hubLocation().ifPresent(player::setCompassTarget);
        player.showTitle(Title.title(Component.text("Nemeton"), Component.text("A árvore antiga protege a clareira.")));
        player.sendMessage("§6Bem-vindo ao §eNemeton§6.");
        player.sendMessage("§7Pegue §f/kit§7, leia §f/guia§7 e converse com os quatro moradores da clareira.");
        player.sendMessage("§7Use §f/mapa§7 para se orientar, §f/troca <jogador>§7 para negociar e §f/lapide§7 após morrer.");
    }

    public void firstJoin(Player player) {
        hubLocation().ifPresent(location -> player.teleportAsync(location).thenRun(() ->
                Bukkit.getScheduler().runTask(plugin, () -> welcome(player))));
        giveGuide(player, true);
        claimStarterKit(player, true);
    }

    public void giveGuide(Player player, boolean quiet) {
        ItemStack guide = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) guide.getItemMeta();
        meta.title(Component.text("Guia do Nemeton"));
        meta.author(Component.text("A comunidade"));
        meta.addPages(
                Component.text("""
                        A clareira

                        A arvore antiga e o coracao do mundo.

                        Aqui nao tem PvP, grife ou claim privado.

                        O beacon marca o retorno seguro.
                        """),
                Component.text("""
                        Comeco

                        /kit
                        Pega ferramentas simples uma unica vez.

                        /guia
                        Recebe este livro de novo.

                        /santuario marcar
                        Protege seu primeiro chunk pessoal.

                        /lapide
                        Mostra sua ultima morte e aponta a bussola.

                        /mapa
                        Entrega o mapa nativo da clareira.
                        """),
                Component.text("""
                        Cla

                        /clan criar <nome> <tag>
                        /clan convidar <jogador>
                        /clan claim
                        /clan chat <msg>

                        Claims de cla precisam ficar conectados.
                        """),
                Component.text("""
                        Trocas

                        /troca <jogador>
                        Abre uma troca segura entre jogadores.

                        Voce pode fechar a tela, pegar item no bau e usar /troca abrir.

                        /troca cancelar devolve tudo.
                        """),
                Component.text("""
                        Viagem

                        /nemeton
                        Volta para a clareira com preparo e cooldown.

                        A ideia e survival raiz: andar, negociar, explorar e se perder um pouco.
                        """),
                Component.text("""
                        Mochila

                        Receita:
                        couro + linha + bau

                        Depois de fabricar, clique com ela ou use /mochila.

                        Sao 27 espacos pessoais. Mochila nao entra dentro de mochila.
                        """),
                Component.text("""
                        Alpha

                        Raids e guerras estao fechadas por enquanto.

                        O objetivo agora e testar survival, clãs, claims e comercio entre jogadores.
                        """));
        guide.setItemMeta(meta);
        addOrDrop(player, guide);
        if (!quiet) player.sendMessage("§aGuia entregue.");
    }

    public void claimStarterKit(Player player) {
        claimStarterKit(player, false);
    }

    public void claimLascadoKit(Player player) {
        if (player.getPersistentDataContainer().has(lascadoKitKey, PersistentDataType.BYTE)) {
            player.sendMessage("§eVocê já pegou o kit lascado.");
            return;
        }
        player.getPersistentDataContainer().set(lascadoKitKey, PersistentDataType.BYTE, (byte) 1);
        addOrDrop(player, named(Material.COMPASS, "§eBússola do Nemeton", "§7Aponte para o farol do mundo."));
        addOrDrop(player, named(Material.COPPER_SWORD, "§6Espada Lascada de Cobre", "§7Pra quem começa torto, mas começa equipado."));
        addOrDrop(player, named(Material.COPPER_PICKAXE, "§6Picareta Lascada de Cobre", "§7Mesma coragem do kit inicial, só que mais brilhosa."));
        addOrDrop(player, named(Material.COPPER_AXE, "§6Machado Lascado de Cobre", "§7Serve pra madeira e pra autoestima."));
        addOrDrop(player, new ItemStack(Material.BREAD, 16));
        addOrDrop(player, new ItemStack(Material.TORCH, 24));
        addOrDrop(player, new ItemStack(Material.OAK_SAPLING, 4));
        addOrDrop(player, named(Material.COPPER_BOOTS, "§6Botas Lascadas de Cobre", "§7Pra sair do Nemeton fazendo barulho."));
        player.sendMessage("§aKit lascado entregue. Agora vai, cobre boy.");
    }

    private void claimStarterKit(Player player, boolean quiet) {
        if (player.getPersistentDataContainer().has(starterKitKey, PersistentDataType.BYTE)) {
            if (!quiet) player.sendMessage("§eVocê já pegou o kit inicial.");
            return;
        }
        player.getPersistentDataContainer().set(starterKitKey, PersistentDataType.BYTE, (byte) 1);
        addOrDrop(player, named(Material.COMPASS, "§eBússola do Nemeton", "§7Aponte para o farol do mundo."));
        addOrDrop(player, new ItemStack(Material.STONE_SWORD));
        addOrDrop(player, new ItemStack(Material.STONE_PICKAXE));
        addOrDrop(player, new ItemStack(Material.STONE_AXE));
        addOrDrop(player, new ItemStack(Material.BREAD, 16));
        addOrDrop(player, new ItemStack(Material.TORCH, 24));
        addOrDrop(player, new ItemStack(Material.OAK_SAPLING, 4));
        addOrDrop(player, new ItemStack(Material.LEATHER_BOOTS));
        if (!quiet) player.sendMessage("§aKit inicial entregue. Boa aventura.");
    }

    private ItemStack named(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        meta.lore(List.of(Component.text(lore)));
        item.setItemMeta(meta);
        return item;
    }

    private void addOrDrop(Player player, ItemStack item) {
        PlayerInventory inventory = player.getInventory();
        inventory.addItem(item).values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private java.util.Optional<Location> hubLocation() {
        World world = Bukkit.getWorld(settings.hub().world());
        if (world == null) return java.util.Optional.empty();
        return java.util.Optional.of(new Location(world, settings.hub().x(), settings.hub().y(), settings.hub().z(), settings.hub().yaw(), settings.hub().pitch()));
    }
}
