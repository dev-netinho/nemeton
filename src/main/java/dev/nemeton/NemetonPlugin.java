package dev.nemeton;

import dev.nemeton.command.NemetonCommands;
import dev.nemeton.config.Settings;
import dev.nemeton.domain.Clan;
import dev.nemeton.integration.*;
import dev.nemeton.listener.*;
import dev.nemeton.persistence.*;
import dev.nemeton.service.*;
import dev.nemeton.state.ServerState;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class NemetonPlugin extends JavaPlugin {
    private Database database;
    private RaidService raidService;

    @Override public void onEnable() {
        saveDefaultConfig(); Settings settings = Settings.load(getConfig());
        try {
            if (settings.database().password().equals("change-me") || settings.database().password().isBlank()) {
                throw new IllegalStateException("Defina DB_PASSWORD antes de iniciar; a senha padrão é recusada.");
            }
            database = new Database(settings.database()); database.migrate(); NemetonRepository repository = new NemetonRepository(database); ServerState state = repository.load();
            RegionGateway regions = new RegionGateway(settings); DiscordBridge discord = new DiscordBridge(settings.discord());
            regions.ensureNemeton(); for (Clan clan : state.clans()) clan.claims().forEach(chunk -> regions.createClanClaim(chunk, clan.members().keySet()));
            state.sanctuaries().forEach((chunk, owner) -> regions.createSanctuary(chunk, owner, state.sanctuaryTrustedPlayers(owner)));
            ClanService clans = new ClanService(state, repository, regions, discord, settings); ClaimService claims = new ClaimService(state, repository, regions, clans, settings);
            AllianceService alliances = new AllianceService(state, repository, clans, settings, regions); clans.setMemberChangeHook(alliances::reconcileClan); claims.setAllianceService(alliances); alliances.reconcileAll(); RaidService raids = new RaidService(this, state, repository, regions, discord, settings); this.raidService = raids; raids.setAllianceService(alliances);
            TeleportService teleports = new TeleportService(this, settings, state); NemetonCommands commands = new NemetonCommands(state, clans, claims, alliances, raids, teleports, repository);
            registerCommand("clan", commands); registerCommand("santuario", commands); registerCommand("raid", commands); registerCommand("nemeton", commands);
            Bukkit.getPluginManager().registerEvents(new ProtectionListener(state, claims, raids, discord), this);
            Bukkit.getPluginManager().registerEvents(new PlayerListener(settings, raids), this);
            Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
                @org.bukkit.event.EventHandler public void move(org.bukkit.event.player.PlayerMoveEvent event) { if (event.getTo() != null) teleports.moved(event.getPlayer()); }
                @org.bukkit.event.EventHandler public void combat(org.bukkit.event.entity.EntityDamageByEntityEvent event) { if (event.getEntity() instanceof org.bukkit.entity.Player victim) teleports.tagCombat(victim.getUniqueId()); if (event.getDamager() instanceof org.bukkit.entity.Player attacker) teleports.tagCombat(attacker.getUniqueId()); }
            }, this);
            Bukkit.getScheduler().runTaskTimer(this, raids::tick, 20L, 20L); Bukkit.getScheduler().runTaskTimerAsynchronously(this, clans::tickWarStates, 1200L, 1200L);
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> discord.pollClanChannels(state.clans(), message ->
                    Bukkit.getScheduler().runTask(this, () -> clans.chatFromDiscord(message.clan(), message.displayName(), message.content()))), 60L, 60L);
            DiscordCommands discordCommands = new DiscordCommands(this, discord, state, clans, raids);
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, discordCommands::registerWhenReady, 200L, 200L);
            Bukkit.getScheduler().runTask(this, raids::recoverOrphans);
            getLogger().info("NemetonCore ativo: " + state.clans().size() + " clãs carregados.");
        } catch (Exception exception) {
            getLogger().severe("Não foi possível iniciar o NemetonCore: " + exception.getMessage()); exception.printStackTrace(); Bukkit.getPluginManager().disablePlugin(this);
        }
    }
    private void registerCommand(String name, NemetonCommands executor) { PluginCommand command = getCommand(name); if (command == null) throw new IllegalStateException("Comando ausente: " + name); command.setExecutor(executor); command.setTabCompleter(executor); }
    @Override public void onDisable() { if (raidService != null) raidService.shutdown(); if (database != null) database.close(); }
}
