package dev.nemeton.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nemeton.config.Settings;
import dev.nemeton.domain.Clan;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class DiscordBridge {
    private static final String API = "https://discord.com/api/v10";
    private final Settings.Discord config;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> lastMessageByChannel = new ConcurrentHashMap<>();
    private final AtomicBoolean polling = new AtomicBoolean();

    public DiscordBridge(Settings.Discord config) { this.config = config; }
    public boolean enabled() { return config.enabled() && !config.botToken().isBlank() && !config.guildId().isBlank(); }

    public CompletableFuture<DiscordResources> createClanResources(Clan clan) {
        if (!enabled()) return CompletableFuture.completedFuture(new DiscordResources(null, null, null));
        return post("/guilds/" + config.guildId() + "/roles", Map.of("name", "Clã " + clan.tag(), "mentionable", true))
                .thenCompose(role -> {
                    String roleId = role.path("id").asText();
                    List<Map<String, Object>> textPermissions = List.of(
                            Map.of("id", config.guildId(), "type", 0, "deny", "1024", "allow", "0"),
                            Map.of("id", roleId, "type", 0, "deny", "0", "allow", "3072"));
                    Map<String, Object> text = new HashMap<>(Map.of("name", "clã-" + clan.tag().toLowerCase(Locale.ROOT), "type", 0, "permission_overwrites", textPermissions));
                    if (!config.clansCategoryId().isBlank()) text.put("parent_id", config.clansCategoryId());
                    return post("/guilds/" + config.guildId() + "/channels", text).thenCompose(textChannel -> {
                        List<Map<String, Object>> voicePermissions = List.of(
                                Map.of("id", config.guildId(), "type", 0, "deny", "1024", "allow", "0"),
                                Map.of("id", roleId, "type", 0, "deny", "0", "allow", "1049600"));
                        Map<String, Object> voice = new HashMap<>(Map.of("name", "Clã " + clan.tag(), "type", 2, "permission_overwrites", voicePermissions));
                        if (!config.clansCategoryId().isBlank()) voice.put("parent_id", config.clansCategoryId());
                        return post("/guilds/" + config.guildId() + "/channels", voice)
                                .thenApply(voiceChannel -> new DiscordResources(roleId, textChannel.path("id").asText(), voiceChannel.path("id").asText()));
                    });
                });
    }

    public CompletableFuture<Void> syncMember(UUID minecraftId, String roleId, boolean add) {
        if (!enabled() || roleId == null) return CompletableFuture.completedFuture(null);
        return linkedDiscordId(minecraftId).map(discordId -> request(add ? "PUT" : "DELETE", "/guilds/" + config.guildId() + "/members/" + discordId + "/roles/" + roleId, null).thenApply(ignored -> (Void) null))
                .orElseGet(() -> CompletableFuture.completedFuture(null));
    }
    public void alert(String message) { if (enabled() && !config.alertsChannelId().isBlank()) postMessage(config.alertsChannelId(), message); }
    public void clanMessage(Clan clan, String message) { if (enabled() && clan.discordTextId() != null) postMessage(clan.discordTextId(), message); }
    public void pollClanChannels(Collection<Clan> clans, Consumer<ClanChatMessage> consumer) {
        if (!enabled() || !polling.compareAndSet(false, true)) return;
        List<CompletableFuture<Void>> requests = clans.stream().filter(c -> c.discordTextId() != null).map(clan -> {
            String channel = clan.discordTextId(), last = lastMessageByChannel.get(channel);
            String path = "/channels/" + channel + "/messages?limit=20" + (last == null ? "" : "&after=" + last);
            return request("GET", path, null).thenAccept(messages -> {
                if (!messages.isArray() || messages.isEmpty()) return;
                List<JsonNode> ordered = new ArrayList<>(); messages.forEach(ordered::add); ordered.sort(Comparator.comparing(node -> node.path("id").asText()));
                String newest = ordered.getLast().path("id").asText();
                if (last != null) for (JsonNode message : ordered) {
                    if (message.path("author").path("bot").asBoolean(false)) continue;
                    String content = message.path("content").asText("").strip(); if (content.isBlank()) continue;
                    linkedMinecraftId(message.path("author").path("id").asText()).filter(clan::contains)
                            .ifPresent(player -> consumer.accept(new ClanChatMessage(clan, player, message.path("author").path("global_name").asText("Discord"), content)));
                }
                lastMessageByChannel.put(channel, newest);
            }).exceptionally(error -> null);
        }).toList();
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> polling.set(false));
    }

    private void postMessage(String channel, String message) { post("/channels/" + channel + "/messages", Map.of("content", message.substring(0, Math.min(1900, message.length())))); }
    private CompletableFuture<JsonNode> post(String path, Object body) { return request("POST", path, body); }
    public CompletableFuture<Void> registerGuildCommands() {
        if (!enabled()) return CompletableFuture.completedFuture(null);
        return request("GET", "/oauth2/applications/@me", null).thenCompose(application -> {
            String applicationId = application.path("id").asText();
            List<Map<String, Object>> commands = List.of(
                    Map.of("name", "clan", "description", "Comandos de clã do Nemeton", "options", List.of(
                            Map.of("type", 1, "name", "status", "description", "Mostra seu clã"),
                            Map.of("type", 1, "name", "recrutar", "description", "Convida um jogador vinculado", "options", List.of(
                                    Map.of("type", 6, "name", "jogador", "description", "Membro do Discord", "required", true))))),
                    Map.of("name", "raid", "description", "Agenda de raids do Nemeton", "options", List.of(
                            Map.of("type", 1, "name", "agenda", "description", "Mostra sua raid pendente"),
                            Map.of("type", 1, "name", "escolher", "description", "Escolhe um horário como defensor", "options", List.of(
                                    Map.of("type", 3, "name", "id", "description", "ID curto da raid", "required", true),
                                    Map.of("type", 4, "name", "horario", "description", "Horário 1, 2 ou 3", "required", true, "min_value", 1, "max_value", 3))))),
                    Map.of("name", "online", "description", "Lista quem está no Minecraft"));
            return request("PUT", "/applications/" + applicationId + "/guilds/" + config.guildId() + "/commands", commands).thenApply(ignored -> (Void) null);
        });
    }
    private CompletableFuture<JsonNode> request(String method, String path, Object body) {
        try {
            HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
            HttpRequest request = HttpRequest.newBuilder(URI.create(API + path)).header("Authorization", "Bot " + config.botToken()).header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15)).method(method, publisher).build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("Discord respondeu " + response.statusCode() + ": " + response.body());
                try { return response.body().isBlank() ? json.nullNode() : json.readTree(response.body()); } catch (Exception e) { throw new IllegalStateException(e); }
            });
        } catch (Exception exception) { return CompletableFuture.failedFuture(exception); }
    }

    private Optional<String> linkedDiscordId(UUID minecraftId) {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("DiscordSRV");
            if (plugin == null) return Optional.empty();
            Method managerMethod = plugin.getClass().getMethod("getAccountLinkManager");
            Object manager = managerMethod.invoke(plugin);
            Method lookup = manager.getClass().getMethod("getDiscordId", UUID.class);
            return Optional.ofNullable((String) lookup.invoke(manager, minecraftId));
        } catch (ReflectiveOperationException ignored) { return Optional.empty(); }
    }
    public Optional<UUID> linkedMinecraftId(String discordId) {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("DiscordSRV"); if (plugin == null) return Optional.empty();
            Object manager = plugin.getClass().getMethod("getAccountLinkManager").invoke(plugin);
            Object value = manager.getClass().getMethod("getUuid", String.class).invoke(manager, discordId);
            return Optional.ofNullable((UUID) value);
        } catch (ReflectiveOperationException ignored) { return Optional.empty(); }
    }
    public record DiscordResources(String roleId, String textId, String voiceId) {}
    public record ClanChatMessage(Clan clan, UUID playerId, String displayName, String content) {}
}
