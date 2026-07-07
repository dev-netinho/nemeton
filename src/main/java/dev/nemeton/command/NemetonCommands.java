package dev.nemeton.command;

import dev.nemeton.config.Settings;
import dev.nemeton.domain.*;
import dev.nemeton.persistence.NemetonRepository;
import dev.nemeton.service.*;
import dev.nemeton.state.ServerState;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class NemetonCommands implements TabExecutor {
    private static final ZoneId ZONE = ZoneId.of("America/Belem");
    private static final DateTimeFormatter SLOT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private final Settings settings; private final ServerState state; private final ClanService clans; private final ClaimService claims; private final AllianceService alliances;
    private final RaidService raids; private final TeleportService teleports; private final ExperienceService experience; private final NemetonRepository repository;
    public NemetonCommands(Settings settings, ServerState state, ClanService clans, ClaimService claims, AllianceService alliances, RaidService raids, TeleportService teleports, ExperienceService experience, NemetonRepository repository) {
        this.settings = settings; this.state = state; this.clans = clans; this.claims = claims; this.alliances = alliances; this.raids = raids; this.teleports = teleports; this.experience = experience; this.repository = repository;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Comando disponível apenas em jogo."); return true; }
        try {
            switch (command.getName().toLowerCase(Locale.ROOT)) {
                case "clan" -> clan(player, args);
                case "santuario" -> sanctuary(player, args);
                case "raid" -> raid(player, args);
                case "nemeton", "spawn" -> teleports.request(player);
                case "guia" -> experience.giveGuide(player, false);
                case "kit" -> experience.claimStarterKit(player);
                default -> { return false; }
            }
        } catch (IllegalArgumentException | DateTimeException exception) { player.sendMessage("§c" + exception.getMessage()); }
        catch (Exception exception) { player.sendMessage("§cOcorreu um erro interno. A administração foi avisada."); Bukkit.getLogger().severe("Nemeton command: " + exception); }
        return true;
    }

    private void clan(Player player, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("ajuda")) { player.sendMessage("§6/clan criar <nome> <tag> | convidar <jogador> | aceitar | sair | expulsar <jogador> | promover <jogador> | claim | unclaim | guerra <ativar|desativar> | cofre <definir|depositar> | aliar <tag> | romper <tag> | acesso <tag> <ativar|desativar> | chat <mensagem> | info"); return; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "criar" -> { requireArgs(args, 3); Clan clan = clans.create(player.getUniqueId(), args[1].replace('_', ' '), args[2]); player.sendMessage("§aClã " + clan.name() + " criado."); }
            case "convidar" -> { requireArgs(args, 2); Clan clan = ownClan(player); OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]); clans.invite(clan, player.getUniqueId(), target.getUniqueId()); player.sendMessage("§aConvite enviado para " + args[1] + "."); }
            case "aceitar" -> player.sendMessage("§aVocê entrou no clã " + clans.accept(player.getUniqueId()).tag() + ".");
            case "sair" -> { clans.leave(player.getUniqueId()); player.sendMessage("§eVocê saiu do clã."); }
            case "expulsar" -> { requireArgs(args, 2); clans.kick(ownClan(player), player.getUniqueId(), Bukkit.getOfflinePlayer(args[1]).getUniqueId()); player.sendMessage("§eMembro removido."); }
            case "promover" -> { requireArgs(args, 2); clans.promote(ownClan(player), player.getUniqueId(), Bukkit.getOfflinePlayer(args[1]).getUniqueId()); player.sendMessage("§aCargo atualizado."); }
            case "claim" -> { claims.claim(ownClan(player), player.getUniqueId(), ChunkPos.of(player.getChunk())); player.sendMessage("§aChunk reivindicado."); }
            case "unclaim" -> { claims.unclaim(ownClan(player), player.getUniqueId(), ChunkPos.of(player.getChunk())); player.sendMessage("§eClaim removido."); }
            case "guerra" -> { requireArgs(args, 2); boolean enable = args[1].equalsIgnoreCase("ativar"); if (enable) requireRaidsEnabled(); clans.setWar(ownClan(player), player.getUniqueId(), enable); player.sendMessage("§eEstado de guerra atualizado."); }
            case "cofre" -> coffer(player, args);
            case "aliar" -> { requireArgs(args, 2); Clan source = ownClan(player), target = clanTag(args[1]); Alliance alliance = alliances.requestOrAccept(source, target, player.getUniqueId()); player.sendMessage(alliance.status() == Alliance.Status.ACTIVE ? "§aAliança firmada." : "§ePedido de aliança enviado."); }
            case "romper" -> { requireArgs(args, 2); alliances.breakAlliance(ownClan(player), clanTag(args[1]), player.getUniqueId()); player.sendMessage("§eAliança rompida; trégua iniciada."); }
            case "acesso" -> { requireArgs(args, 3); alliances.setAccess(ownClan(player), clanTag(args[1]), player.getUniqueId(), args[2].equalsIgnoreCase("ativar")); player.sendMessage("§aAcesso da aliança atualizado."); }
            case "chat" -> { requireArgs(args, 2); Clan clan = ownClan(player); String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length)); clans.chat(clan, player.getName(), message); }
            case "info" -> { Clan clan = ownClan(player); player.sendMessage("§6" + clan.name() + " [" + clan.tag() + "] §7— membros: " + clan.members().size() + ", claims: " + clan.claims().size() + "/" + clans.claimLimit(clan) + ", guerra: " + clan.warState() + ", cofre: " + clan.cofferDiamonds() + "♦"); }
            default -> throw new IllegalArgumentException("Subcomando de clã desconhecido.");
        }
    }

    private void coffer(Player player, String[] args) {
        requireArgs(args, 2); Clan clan = ownClan(player); clans.requireManager(clan, player.getUniqueId());
        if (args[1].equalsIgnoreCase("definir")) {
            if (!clan.claims().contains(ChunkPos.of(player.getChunk()))) throw new IllegalArgumentException("O cofre precisa ficar em território do clã.");
            var target = player.getTargetBlockExact(5); if (target == null || target.getType() != Material.LODESTONE) throw new IllegalArgumentException("Olhe para uma magnetita a até cinco blocos.");
            clan.setCoffer(new Clan.BlockPoint(target.getWorld().getName(), target.getX(), target.getY(), target.getZ())); repository.saveClanRuntime(clan); player.sendMessage("§aCofre de guerra definido."); return;
        }
        if (args[1].equalsIgnoreCase("depositar")) {
            requireArgs(args, 3); int amount = Integer.parseInt(args[2]); if (amount <= 0 || count(player, Material.DIAMOND) < amount) throw new IllegalArgumentException("Diamantes insuficientes.");
            remove(player, Material.DIAMOND, amount); clan.deposit(amount); repository.saveClanRuntime(clan); player.sendMessage("§a" + amount + " diamantes depositados."); return;
        }
        throw new IllegalArgumentException("Use definir ou depositar.");
    }

    private void sanctuary(Player player, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("ajuda")) { player.sendMessage("§6/santuario marcar | expandir | remover | confiar <jogador> | desconfiar <jogador>"); return; }
        ChunkPos chunk = ChunkPos.of(player.getChunk()); switch (args[0].toLowerCase(Locale.ROOT)) {
            case "marcar", "expandir" -> { claims.sanctuary(player.getUniqueId(), chunk); player.sendMessage("§aChunk adicionado ao santuário."); }
            case "remover" -> { claims.removeSanctuary(player.getUniqueId(), chunk); player.sendMessage("§eChunk removido do santuário."); }
            case "confiar", "desconfiar" -> { requireArgs(args, 2); boolean add = args[0].equalsIgnoreCase("confiar"); claims.trust(player.getUniqueId(), Bukkit.getOfflinePlayer(args[1]).getUniqueId(), add); player.sendMessage("§aConfiança atualizada."); }
            default -> throw new IllegalArgumentException("Subcomando de santuário desconhecido.");
        }
    }

    private void raid(Player player, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("ajuda")) { player.sendMessage("§6/raid declarar <tag> <diamantes> <data1> <data2> <data3> | agendar <id> <1|2|3> | status [id] | premio"); player.sendMessage(settings.war().raidsEnabled() ? "§7Data: 2026-07-08T20:00 (horário de Belém)" : "§eRaids estão desativadas neste alpha."); return; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "declarar" -> { requireRaidsEnabled(); requireArgs(args, 6); Clan attacker = ownClan(player), defender = clanTag(args[1]); int stake = Integer.parseInt(args[2]); List<Instant> slots = List.of(parseSlot(args[3]), parseSlot(args[4]), parseSlot(args[5])); Raid created = raids.declare(attacker, defender, player.getUniqueId(), stake, slots); player.sendMessage("§aRaid declarada: " + RaidService.shortId(created.id())); }
            case "agendar" -> { requireRaidsEnabled(); requireArgs(args, 3); Raid selected = raidId(args[1]); raids.schedule(selected, ownClan(player), player.getUniqueId(), Integer.parseInt(args[2])); player.sendMessage("§aHorário confirmado."); }
            case "status" -> { Raid selected = args.length >= 2 ? raidId(args[1]) : state.clanOf(player.getUniqueId()).flatMap(c -> state.activeRaidForClan(c.id())).orElseThrow(() -> new IllegalArgumentException("Nenhuma raid encontrada.")); player.sendMessage("§6Raid " + RaidService.shortId(selected.id()) + " §7— " + selected.state() + ", captura: " + selected.captureSeconds() + "s, início: " + selected.startsAt()); }
            case "premio" -> player.sendMessage("§ePrêmios de raid são creditados diretamente no cofre do clã vencedor.");
            default -> throw new IllegalArgumentException("Subcomando de raid desconhecido.");
        }
    }

    private Clan ownClan(Player player) { return state.clanOf(player.getUniqueId()).orElseThrow(() -> new IllegalArgumentException("Você não pertence a um clã.")); }
    private Clan clanTag(String tag) { return state.clanByTag(tag).orElseThrow(() -> new IllegalArgumentException("Clã não encontrado.")); }
    private Raid raidId(String id) { return raids.byShortId(id).orElseThrow(() -> new IllegalArgumentException("Raid não encontrada.")); }
    private Instant parseSlot(String text) { return LocalDateTime.parse(text, SLOT_FORMAT).atZone(ZONE).toInstant(); }
    private void requireRaidsEnabled() { if (!settings.war().raidsEnabled()) throw new IllegalArgumentException("Raids e modo de guerra estão desativados neste alpha."); }
    private void requireArgs(String[] args, int count) { if (args.length < count) throw new IllegalArgumentException("Argumentos insuficientes. Use o comando de ajuda."); }
    private int count(Player player, Material type) { return Arrays.stream(player.getInventory().getContents()).filter(Objects::nonNull).filter(i -> i.getType() == type).mapToInt(ItemStack::getAmount).sum(); }
    private void remove(Player player, Material type, int amount) { int left = amount; for (ItemStack item : player.getInventory().getContents()) { if (item == null || item.getType() != type) continue; int take = Math.min(left, item.getAmount()); item.setAmount(item.getAmount() - take); left -= take; if (left == 0) return; } }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of(); return switch (command.getName()) {
            case "clan" -> List.of("ajuda", "criar", "convidar", "aceitar", "sair", "expulsar", "promover", "claim", "unclaim", "guerra", "cofre", "aliar", "romper", "acesso", "chat", "info");
            case "santuario" -> List.of("ajuda", "marcar", "expandir", "remover", "confiar", "desconfiar");
            case "raid" -> List.of("ajuda", "declarar", "agendar", "status", "premio"); default -> List.of(); };
    }
}
