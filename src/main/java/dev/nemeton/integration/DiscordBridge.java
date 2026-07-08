package dev.nemeton.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nemeton.config.Settings;
import dev.nemeton.domain.Clan;
import dev.nemeton.domain.ClanRole;
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
    private static final long VIEW_CHANNEL = 1L << 10;
    private static final long SEND_MESSAGES = 1L << 11;
    private static final long READ_HISTORY = 1L << 16;
    private static final long CREATE_PUBLIC_THREADS = 1L << 35;
    private static final long CREATE_PRIVATE_THREADS = 1L << 36;
    private static final long SEND_MESSAGES_IN_THREADS = 1L << 38;
    private static final long THREAD_PERMISSIONS = CREATE_PUBLIC_THREADS | CREATE_PRIVATE_THREADS | SEND_MESSAGES_IN_THREADS;
    private static final long CONNECT = 1L << 20;
    private static final long SPEAK = 1L << 21;
    private final Settings.Discord config;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> lastMessageByChannel = new ConcurrentHashMap<>();
    private final Map<UUID, String> appliedClanIdentity = new ConcurrentHashMap<>();
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicBoolean suggestionsPolling = new AtomicBoolean();
    private volatile String lastSuggestionMessageId;

    public DiscordBridge(Settings.Discord config) { this.config = config; }
    public boolean enabled() { return config.enabled() && !config.botToken().isBlank() && !config.guildId().isBlank(); }

    public CompletableFuture<DiscordResources> createClanResources(Clan clan) {
        if (!enabled()) return CompletableFuture.completedFuture(new DiscordResources(null, null, null));
        int color = 0x5B9B73 + Math.floorMod(clan.tag().hashCode(), 0x202020);
        return post("/guilds/" + config.guildId() + "/roles", Map.of("name", "🛡️ Clã • " + clan.tag(), "color", color, "hoist", true, "mentionable", true))
                .thenCompose(role -> {
                    String roleId = role.path("id").asText();
                    List<Map<String, Object>> textPermissions = new ArrayList<>();
                    textPermissions.add(overwrite(config.guildId(), 0, 0, VIEW_CHANNEL | THREAD_PERMISSIONS));
                    textPermissions.add(overwrite(roleId, 0, VIEW_CHANNEL | SEND_MESSAGES | READ_HISTORY, 0));
                    addBotOverwrite(textPermissions, VIEW_CHANNEL | SEND_MESSAGES | READ_HISTORY);
                    Map<String, Object> text = new HashMap<>(Map.of("name", "🏰・clã-" + clan.tag().toLowerCase(Locale.ROOT), "type", 0, "permission_overwrites", textPermissions,
                            "topic", "Quartel privado de " + clan.name() + " [" + clan.tag() + "]. O chat daqui também chega ao Minecraft."));
                    if (!config.clansCategoryId().isBlank()) text.put("parent_id", config.clansCategoryId());
                    return post("/guilds/" + config.guildId() + "/channels", text).thenCompose(textChannel -> {
                        List<Map<String, Object>> voicePermissions = new ArrayList<>();
                        voicePermissions.add(overwrite(config.guildId(), 0, 0, VIEW_CHANNEL | CONNECT));
                        voicePermissions.add(overwrite(roleId, 0, VIEW_CHANNEL | CONNECT | SPEAK, 0));
                        addBotOverwrite(voicePermissions, VIEW_CHANNEL | CONNECT | SPEAK);
                        Map<String, Object> voice = new HashMap<>(Map.of("name", "🔊・" + clan.tag(), "type", 2, "permission_overwrites", voicePermissions));
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
    public CompletableFuture<Void> syncClanIdentity(UUID minecraftId, String clanRoleId, ClanRole rank) {
        if (!enabled()) return CompletableFuture.completedFuture(null);
        String desired = Objects.toString(clanRoleId, "") + ":" + rank.name();
        if (desired.equals(appliedClanIdentity.get(minecraftId))) return CompletableFuture.completedFuture(null);
        return linkedDiscordId(minecraftId).map(discordId -> {
            List<String> managed = configuredRankRoles();
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (String role : managed) chain = chain.thenCompose(ignored -> setRole(discordId, role, false));
            if (clanRoleId != null && !clanRoleId.isBlank()) chain = chain.thenCompose(ignored -> setRole(discordId, clanRoleId, true));
            String rankRole = switch (rank) {
                case LEADER -> config.clanLeaderRoleId();
                case OFFICER -> config.clanOfficerRoleId();
                case MEMBER -> config.clanMemberRoleId();
            };
            if (!rankRole.isBlank()) chain = chain.thenCompose(ignored -> setRole(discordId, rankRole, true));
            return chain.whenComplete((ignored, error) -> { if (error == null) appliedClanIdentity.put(minecraftId, desired); });
        }).orElseGet(() -> CompletableFuture.completedFuture(null));
    }
    public CompletableFuture<Void> removeClanIdentity(UUID minecraftId, String clanRoleId) {
        if (!enabled()) return CompletableFuture.completedFuture(null);
        return linkedDiscordId(minecraftId).map(discordId -> {
            List<String> roles = new ArrayList<>(configuredRankRoles());
            if (clanRoleId != null && !clanRoleId.isBlank()) roles.add(clanRoleId);
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (String role : roles) chain = chain.thenCompose(ignored -> setRole(discordId, role, false));
            return chain.whenComplete((ignored, error) -> { if (error == null) appliedClanIdentity.remove(minecraftId); });
        }).orElseGet(() -> CompletableFuture.completedFuture(null));
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

    public void pollSuggestionVotes() {
        if (!enabled() || config.suggestionsChannelId().isBlank() || !suggestionsPolling.compareAndSet(false, true)) return;
        String path = "/channels/" + config.suggestionsChannelId() + "/messages?limit=50"
                + (lastSuggestionMessageId == null ? "" : "&after=" + lastSuggestionMessageId);
        request("GET", path, null).thenCompose(messages -> {
            if (!messages.isArray() || messages.isEmpty()) return CompletableFuture.completedFuture(null);
            List<JsonNode> ordered = new ArrayList<>();
            messages.forEach(ordered::add);
            ordered.sort(Comparator.comparing(node -> node.path("id").asText()));
            List<CompletableFuture<JsonNode>> reactions = new ArrayList<>();
            for (JsonNode message : ordered) {
                if (message.path("author").path("bot").asBoolean(false)) continue;
                String messageId = message.path("id").asText();
                reactions.add(request("PUT", "/channels/" + config.suggestionsChannelId() + "/messages/" + messageId + "/reactions/%E2%9C%85/@me", null));
                reactions.add(request("PUT", "/channels/" + config.suggestionsChannelId() + "/messages/" + messageId + "/reactions/%E2%9D%8C/@me", null));
            }
            String newest = ordered.getLast().path("id").asText();
            return CompletableFuture.allOf(reactions.toArray(CompletableFuture[]::new)).thenRun(() -> lastSuggestionMessageId = newest);
        }).exceptionally(error -> null).whenComplete((ignored, error) -> suggestionsPolling.set(false));
    }

    private void postMessage(String channel, String message) { post("/channels/" + channel + "/messages", Map.of("content", message.substring(0, Math.min(1900, message.length())))); }
    private CompletableFuture<JsonNode> post(String path, Object body) { return request("POST", path, body); }
    private CompletableFuture<Void> setRole(String discordId, String roleId, boolean add) {
        if (roleId == null || roleId.isBlank()) return CompletableFuture.completedFuture(null);
        return request(add ? "PUT" : "DELETE", "/guilds/" + config.guildId() + "/members/" + discordId + "/roles/" + roleId, null).thenApply(ignored -> null);
    }
    private List<String> configuredRankRoles() {
        return List.of(config.clanLeaderRoleId(), config.clanOfficerRoleId(), config.clanMemberRoleId()).stream().filter(value -> value != null && !value.isBlank()).toList();
    }
    private Map<String, Object> overwrite(String id, int type, long allow, long deny) {
        return Map.of("id", id, "type", type, "allow", Long.toString(allow), "deny", Long.toString(deny));
    }
    private void addBotOverwrite(List<Map<String, Object>> permissions, long allow) {
        if (!config.botUserId().isBlank()) permissions.add(overwrite(config.botUserId(), 1, allow, 0));
    }
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
