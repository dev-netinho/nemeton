package dev.nemeton.service;

import dev.nemeton.integration.BedrockForms;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HexFormat;
import java.util.UUID;
import java.util.logging.Level;

/** Sends the Java visual pack while Bedrock receives the equivalent pack through Geyser. */
public final class ResourcePackService implements Listener {
    private static final String DEFAULT_URL = "https://raw.githubusercontent.com/dev-netinho/nemeton/main/resourcepacks/dist/Nemeton-Java.zip?sha1=55c542426ba2d90ed49742df2f1f4716ce31ad7a";
    private static final String DEFAULT_SHA1 = "55c542426ba2d90ed49742df2f1f4716ce31ad7a";

    private final JavaPlugin plugin;

    public ResourcePackService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (BedrockForms.isBedrock(player)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> BedrockForms.sendSimple(plugin, player,
                    "Textura Nemeton",
                    "Seu Bedrock recebe Nemeton + Faithful 32x pelo Geyser ao conectar.\n\nO pack cobre o Minecraft inteiro e os itens autorais. Se algum item aparecer roxo/preto, limpe o cache de packs do servidor e entre novamente.",
                    ignored -> {},
                    "Entendi"), 80L);
            return;
        }
        String url = plugin.getConfig().getString("resource-pack.java-url", DEFAULT_URL);
        String hash = plugin.getConfig().getString("resource-pack.java-sha1", DEFAULT_SHA1);
        boolean forced = plugin.getConfig().getBoolean("resource-pack.force-java", false);
        if (url == null || url.isBlank() || hash == null || !hash.matches("(?i)[0-9a-f]{40}")) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> send(player, url, hash, forced), 60L);
    }

    private void send(Player player, String url, String hash, boolean forced) {
        if (!player.isOnline()) return;
        try {
            player.setResourcePack(UUID.nameUUIDFromBytes(url.getBytes()), url, HexFormat.of().parseHex(hash),
                    Component.text("Nemeton + Faithful 32x: textura completa Vanilla+ e itens autorais."),
                    forced);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().log(Level.WARNING, "SHA-1 inválido do resource pack Java", exception);
        }
    }
}
