package dev.nemeton.integration;

import dev.nemeton.domain.*;
import dev.nemeton.service.*;
import dev.nemeton.state.ServerState;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Uses DiscordSRV's relocated JDA through reflection. This avoids shipping a
 * second JDA copy or opening a second gateway session with the same bot token.
 */
public final class DiscordCommands {
    private final Plugin owner; private final DiscordBridge discord; private final ServerState state;
    private final ClanService clans; private final RaidService raids; private final AtomicBoolean registered = new AtomicBoolean(); private final AtomicBoolean registering = new AtomicBoolean();
    private Object listenerProxy;

    public DiscordCommands(Plugin owner, DiscordBridge discord, ServerState state, ClanService clans, RaidService raids) {
        this.owner = owner; this.discord = discord; this.state = state; this.clans = clans; this.raids = raids;
    }

    public void registerWhenReady() {
        if (!discord.enabled() || registered.get() || !registering.compareAndSet(false, true)) return;
        try {
            Plugin discordSrv = Bukkit.getPluginManager().getPlugin("DiscordSRV"); if (discordSrv == null || !discordSrv.isEnabled()) return;
            Object jda = discordSrv.getClass().getMethod("getJda").invoke(discordSrv); if (jda == null) return;
            discord.registerGuildCommands().join();
            ClassLoader loader = discordSrv.getClass().getClassLoader();
            Class<?> eventListener = Class.forName("github.scarsz.discordsrv.dependencies.jda.api.hooks.EventListener", true, loader);
            listenerProxy = Proxy.newProxyInstance(loader, new Class<?>[]{eventListener}, (proxy, method, args) -> {
                if (method.getName().equals("toString")) return "NemetonDiscordListener";
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == args[0];
                if (method.getName().equals("onEvent") && args != null && args.length == 1) handleEvent(args[0]); return null;
            });
            Method addListener = Arrays.stream(jda.getClass().getMethods()).filter(m -> m.getName().equals("addEventListener") && m.getParameterCount() == 1).findFirst().orElseThrow();
            addListener.invoke(jda, (Object) new Object[]{listenerProxy});
            registered.set(true); owner.getLogger().info("Comandos slash do Discord registrados.");
        } catch (Exception exception) { owner.getLogger().warning("DiscordSRV ainda não está pronto para comandos slash: " + rootMessage(exception)); }
        finally { registering.set(false); }
    }

    private void handleEvent(Object event) {
        if (!event.getClass().getSimpleName().equals("SlashCommandInteractionEvent")) return;
        try {
            String command = string(event, "getName"), subcommand = nullableString(event, "getSubcommandName");
            Object user = invoke(event, "getUser"); String discordId = string(user, "getId");
            UUID minecraftId = discord.linkedMinecraftId(discordId).orElse(null);
            if (command.equals("online")) { Bukkit.getScheduler().runTask(owner, () -> reply(event, onlinePlayers())); return; }
            if (minecraftId == null) { reply(event, "Vincule sua conta Minecraft ao Discord antes de usar este comando."); return; }
            Bukkit.getScheduler().runTask(owner, () -> execute(event, command, subcommand, minecraftId));
        } catch (Exception exception) { reply(event, "Não consegui processar o comando: " + rootMessage(exception)); }
    }

    private void execute(Object event, String command, String subcommand, UUID player) {
        try {
            if (command.equals("clan") && "status".equals(subcommand)) {
                Clan clan = state.clanOf(player).orElseThrow(() -> new IllegalArgumentException("Você ainda não pertence a um clã."));
                reply(event, "**" + clan.name() + " [" + clan.tag() + "]** — " + clan.members().size() + " membros, " + clan.claims().size() + "/" + clans.claimLimit(clan) + " claims, guerra: " + clan.warState() + ", cofre: " + clan.cofferDiamonds() + "♦"); return;
            }
            if (command.equals("clan") && "recrutar".equals(subcommand)) {
                Clan clan = state.clanOf(player).orElseThrow(() -> new IllegalArgumentException("Você ainda não pertence a um clã."));
                Object option = option(event, "jogador"), targetUser = invoke(option, "getAsUser"); String targetDiscord = string(targetUser, "getId");
                UUID target = discord.linkedMinecraftId(targetDiscord).orElseThrow(() -> new IllegalArgumentException("Esse usuário ainda não vinculou o Minecraft."));
                clans.invite(clan, player, target); reply(event, "Convite enviado. A pessoa pode entrar com `/clan aceitar` no Minecraft."); return;
            }
            if (command.equals("raid") && "agenda".equals(subcommand)) {
                Clan clan = state.clanOf(player).orElseThrow(() -> new IllegalArgumentException("Você ainda não pertence a um clã."));
                Raid raid = state.activeRaidForClan(clan.id()).orElseThrow(() -> new IllegalArgumentException("Seu clã não possui raid pendente."));
                String start = raid.startsAt() == null ? "a definir" : "<t:" + raid.startsAt().getEpochSecond() + ":F>";
                reply(event, "Raid `" + RaidService.shortId(raid.id()) + "` — **" + raid.state() + "**, início " + start + ", aposta " + raid.stake() + "♦."); return;
            }
            if (command.equals("raid") && "escolher".equals(subcommand)) {
                Clan clan = state.clanOf(player).orElseThrow(() -> new IllegalArgumentException("Você ainda não pertence a um clã."));
                String id = string(option(event, "id"), "getAsString"); int slot = ((Number) invoke(option(event, "horario"), "getAsLong")).intValue();
                Raid raid = raids.byShortId(id).orElseThrow(() -> new IllegalArgumentException("Raid não encontrada.")); raids.schedule(raid, clan, player, slot); reply(event, "Horário " + slot + " confirmado."); return;
            }
            reply(event, "Comando não reconhecido.");
        } catch (Exception exception) { reply(event, rootMessage(exception)); }
    }

    private String onlinePlayers() { List<String> names = Bukkit.getOnlinePlayers().stream().map(org.bukkit.entity.Player::getName).sorted().toList(); return names.isEmpty() ? "Ninguém está online agora." : "**Online (" + names.size() + "):** " + String.join(", ", names); }
    private Object option(Object event, String name) throws ReflectiveOperationException { Object value = event.getClass().getMethod("getOption", String.class).invoke(event, name); if (value == null) throw new IllegalArgumentException("Opção ausente: " + name); return value; }
    private Object invoke(Object target, String method) throws ReflectiveOperationException { return target.getClass().getMethod(method).invoke(target); }
    private String string(Object target, String method) throws ReflectiveOperationException { return String.valueOf(invoke(target, method)); }
    private String nullableString(Object target, String method) throws ReflectiveOperationException { Object value = invoke(target, method); return value == null ? null : value.toString(); }
    private void reply(Object event, String message) {
        try {
            Object action = event.getClass().getMethod("reply", String.class).invoke(event, message.substring(0, Math.min(1900, message.length())));
            action = action.getClass().getMethod("setEphemeral", boolean.class).invoke(action, true); action.getClass().getMethod("queue").invoke(action);
        } catch (Exception exception) { owner.getLogger().warning("Falha ao responder interação: " + rootMessage(exception)); }
    }
    private static String rootMessage(Throwable error) { Throwable current = error; while (current.getCause() != null) current = current.getCause(); return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage(); }
}
