package dev.nemeton.command;

import dev.nemeton.config.Settings;
import dev.nemeton.domain.*;
import dev.nemeton.integration.BedrockForms;
import dev.nemeton.persistence.NemetonRepository;
import dev.nemeton.service.*;
import dev.nemeton.state.ServerState;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class NemetonCommands implements TabExecutor {
    private static final ZoneId ZONE = ZoneId.of("America/Belem");
    private static final DateTimeFormatter SLOT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private final JavaPlugin plugin;
    private final Settings settings; private final ServerState state; private final ClanService clans; private final ClaimService claims; private final AllianceService alliances;
    private final RaidService raids; private final TeleportService teleports; private final ExperienceService experience; private final NemetonRepository repository;
    public NemetonCommands(JavaPlugin plugin, Settings settings, ServerState state, ClanService clans, ClaimService claims, AllianceService alliances, RaidService raids, TeleportService teleports, ExperienceService experience, NemetonRepository repository) {
        this.plugin = plugin; this.settings = settings; this.state = state; this.clans = clans; this.claims = claims; this.alliances = alliances; this.raids = raids; this.teleports = teleports; this.experience = experience; this.repository = repository;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Comando disponível apenas em jogo."); return true; }
        try {
            switch (command.getName().toLowerCase(Locale.ROOT)) {
                case "clan" -> clan(player, args);
                case "santuario" -> sanctuary(player, args);
                case "raid" -> raid(player, args);
                case "menu", "painel" -> menu(player);
                case "nemeton", "spawn" -> teleports.request(player);
                case "guia" -> {
                    if (!bedrockGuide(player)) experience.giveGuide(player, false);
                }
                case "kit" -> kit(player, args);
                default -> { return false; }
            }
        } catch (IllegalArgumentException | DateTimeException exception) { player.sendMessage("§c" + exception.getMessage()); }
        catch (Exception exception) { player.sendMessage("§cOcorreu um erro interno. A administração foi avisada."); Bukkit.getLogger().severe("Nemeton command: " + exception); }
        return true;
    }

    private void kit(Player player, String[] args) {
        if (args.length == 0) {
            experience.claimStarterKit(player);
            return;
        }
        if (args[0].equalsIgnoreCase("lascado")) {
            experience.claimLascadoKit(player);
            return;
        }
        throw new IllegalArgumentException("Use /kit ou /kit lascado.");
    }

    private void clan(Player player, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("ajuda")) {
            if (openClanMenu(player)) return;
            player.sendMessage("§6/clan criar <nome> <tag> | convidar <jogador> | aceitar | sair | expulsar <jogador> | promover <jogador> | claim | unclaim | confiar <jogador> | desconfiar <jogador> | cofre <definir|depositar> | aliar <tag> | romper <tag> | acesso <tag> <ativar|desativar> | chat <mensagem> | info");
            player.sendMessage("§cAo entrar em um clã, você aceita participar de ataques e defesas. Santuários pessoais continuam invioláveis.");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "criar" -> { requireArgs(args, 3); Clan clan = clans.create(player.getUniqueId(), args[1].replace('_', ' '), args[2]); player.sendMessage("§aClã " + clan.name() + " criado."); }
            case "convidar" -> { requireArgs(args, 2); Clan clan = ownClan(player); OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]); clans.invite(clan, player.getUniqueId(), target.getUniqueId()); player.sendMessage("§aConvite enviado para " + args[1] + "."); }
            case "aceitar" -> player.sendMessage("§aVocê entrou no clã " + clans.accept(player.getUniqueId()).tag() + ".");
            case "sair" -> { clans.leave(player.getUniqueId()); player.sendMessage("§eVocê saiu do clã."); }
            case "expulsar" -> { requireArgs(args, 2); clans.kick(ownClan(player), player.getUniqueId(), Bukkit.getOfflinePlayer(args[1]).getUniqueId()); player.sendMessage("§eMembro removido."); }
            case "promover" -> { requireArgs(args, 2); clans.promote(ownClan(player), player.getUniqueId(), Bukkit.getOfflinePlayer(args[1]).getUniqueId()); player.sendMessage("§aCargo atualizado."); }
            case "claim" -> { claims.claim(ownClan(player), player.getUniqueId(), ChunkPos.of(player.getChunk())); player.sendMessage("§aChunk reivindicado."); }
            case "unclaim" -> { claims.unclaim(ownClan(player), player.getUniqueId(), ChunkPos.of(player.getChunk())); player.sendMessage("§eClaim removido."); }
            case "guerra" -> clans.setWar(ownClan(player), player.getUniqueId(), true);
            case "confiar", "desconfiar" -> { requireArgs(args, 2); boolean add = args[0].equalsIgnoreCase("confiar"); claims.trustClan(ownClan(player), player.getUniqueId(), Bukkit.getOfflinePlayer(args[1]).getUniqueId(), add); player.sendMessage(add ? "§aJogador autorizado no território do clã." : "§eAcesso ao território do clã removido."); }
            case "cofre" -> coffer(player, args);
            case "aliar" -> { requireArgs(args, 2); Clan source = ownClan(player), target = clanTag(args[1]); Alliance alliance = alliances.requestOrAccept(source, target, player.getUniqueId()); player.sendMessage(alliance.status() == Alliance.Status.ACTIVE ? "§aAliança firmada." : "§ePedido de aliança enviado."); }
            case "romper" -> { requireArgs(args, 2); alliances.breakAlliance(ownClan(player), clanTag(args[1]), player.getUniqueId()); player.sendMessage("§eAliança rompida; trégua iniciada."); }
            case "acesso" -> { requireArgs(args, 3); alliances.setAccess(ownClan(player), clanTag(args[1]), player.getUniqueId(), args[2].equalsIgnoreCase("ativar")); player.sendMessage("§aAcesso da aliança atualizado."); }
            case "chat" -> { requireArgs(args, 2); Clan clan = ownClan(player); String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length)); clans.chat(clan, player.getName(), message); }
            case "info" -> { Clan clan = ownClan(player); player.sendMessage("§6" + clan.name() + " [" + clan.tag() + "] §7— membros: " + clan.members().size() + ", claims: " + clan.claims().size() + "/" + clans.claimLimit(clan) + ", combate: §cATIVO§7, confiáveis: " + state.clanTrustedPlayers(clan.id()).size() + ", cofre: " + clan.cofferDiamonds() + "♦"); }
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
        if (args.length == 0 || args[0].equalsIgnoreCase("ajuda")) {
            if (openSanctuaryMenu(player)) return;
            player.sendMessage("§6/santuario marcar | expandir | remover | confiar <jogador> | desconfiar <jogador>");
            return;
        }
        ChunkPos chunk = ChunkPos.of(player.getChunk()); switch (args[0].toLowerCase(Locale.ROOT)) {
            case "marcar", "expandir" -> { claims.sanctuary(player.getUniqueId(), chunk); player.sendMessage("§aChunk adicionado ao santuário."); }
            case "remover" -> { claims.removeSanctuary(player.getUniqueId(), chunk); player.sendMessage("§eChunk removido do santuário."); }
            case "confiar", "desconfiar" -> { requireArgs(args, 2); boolean add = args[0].equalsIgnoreCase("confiar"); claims.trust(player.getUniqueId(), Bukkit.getOfflinePlayer(args[1]).getUniqueId(), add); player.sendMessage("§aConfiança atualizada."); }
            default -> throw new IllegalArgumentException("Subcomando de santuário desconhecido.");
        }
    }

    private void raid(Player player, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("ajuda")) {
            if (openRaidMenu(player)) return;
            player.sendMessage("§6/raid declarar <tag> <diamantes> <data1> <data2> <data3> | agendar <id> <1|2|3> | status [id] | premio");
            player.sendMessage(settings.war().raidsEnabled() ? "§7Data: 2026-07-08T20:00 (horário de Belém)" : "§eRaids estão desativadas neste alpha.");
            return;
        }
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
    private void requireRaidsEnabled() { if (!settings.war().raidsEnabled()) throw new IllegalArgumentException("Raids estão temporariamente pausadas pela administração."); }
    private void requireArgs(String[] args, int count) { if (args.length < count) throw new IllegalArgumentException("Argumentos insuficientes. Use o comando de ajuda."); }
    private int count(Player player, Material type) { return Arrays.stream(player.getInventory().getContents()).filter(Objects::nonNull).filter(i -> i.getType() == type).mapToInt(ItemStack::getAmount).sum(); }
    private void remove(Player player, Material type, int amount) { int left = amount; for (ItemStack item : player.getInventory().getContents()) { if (item == null || item.getType() != type) continue; int take = Math.min(left, item.getAmount()); item.setAmount(item.getAmount() - take); left -= take; if (left == 0) return; } }

    private boolean menu(Player player) {
        if (!BedrockForms.sendSimple(plugin, player, "Painel do Nemeton",
                "Tudo que você precisa sem decorar comando.\n\nA experiência Bedrock usa telas nativas; Java continua com inventários/chat quando fizer sentido.",
                index -> {
                    switch (index) {
                        case 0 -> player.performCommand("guia");
                        case 1 -> player.performCommand("clan");
                        case 2 -> player.performCommand("santuario");
                        case 3 -> player.performCommand("troca");
                        case 4 -> player.performCommand("mapa");
                        case 5 -> player.performCommand("mochila");
                        case 6 -> player.performCommand("lapide");
                        case 7 -> player.performCommand("mods");
                        case 8 -> player.performCommand("nemeton");
                        default -> { }
                    }
                },
                "Primeiros passos", "Clãs", "Santuário", "Troca", "Mapa", "Mochila", "Lápide", "Nemeton+", "Voltar ao Nemeton", "Fechar")) {
            player.sendMessage("§6Painel do Nemeton");
            player.sendMessage("§7Use: §f/guia§7, §f/clan§7, §f/santuario§7, §f/troca§7, §f/mapa§7, §f/mochila§7, §f/lapide§7, §f/mods§7.");
            return true;
        }
        return true;
    }

    private boolean bedrockGuide(Player player) {
        return BedrockForms.sendSimple(plugin, player, "Guia do Nemeton",
                "A clareira é segura: sem PvP, sem grife e sem claim privado.\n\n"
                        + "Depois dos portões começa o survival. Marque seu santuário, negocie com jogadores e forme clãs.",
                index -> {
                    switch (index) {
                        case 0 -> experience.giveGuide(player, false);
                        case 1 -> experience.claimStarterKit(player);
                        case 2 -> player.performCommand("mapa");
                        case 3 -> player.performCommand("santuario");
                        case 4 -> player.performCommand("clan");
                        case 5 -> player.performCommand("troca");
                        case 6 -> player.performCommand("mods");
                        default -> { }
                    }
                },
                "Receber livro", "Pegar /kit", "Abrir mapa", "Santuário", "Clãs", "Troca", "Nemeton+", "Fechar");
    }

    private boolean openClanMenu(Player player) {
        Optional<Clan> maybeClan = state.clanOf(player.getUniqueId());
        String status = maybeClan.map(clan -> "Seu clã: " + clan.name() + " [" + clan.tag() + "]\n"
                        + "Membros: " + clan.members().size() + "\nClaims: " + clan.claims().size() + "/" + clans.claimLimit(clan) + "\n"
                        + "Combate: ATIVO\nCofre: " + clan.cofferDiamonds() + " diamantes")
                .orElse("Você ainda não pertence a um clã. Seu santuário pessoal é inviolável.");
        List<String> buttons = maybeClan.isPresent()
                ? List.of("Info", "Claimar chunk", "Remover claim", "Convidar jogador", "Acesso ao território", "Cofre", "Aliança", "Sair do clã", "Fechar")
                : List.of("Criar clã", "Aceitar convite", "Ajuda", "Fechar");
        return BedrockForms.sendSimple(plugin, player, "Clãs", status, index -> {
            if (maybeClan.isEmpty()) {
                switch (index) {
                    case 0 -> clanCreateForm(player);
                    case 1 -> runSafely(player, () -> player.sendMessage("§aVocê entrou no clã " + clans.accept(player.getUniqueId()).tag() + "."));
                    case 2 -> player.performCommand("clan ajuda");
                    default -> { }
                }
                return;
            }
            switch (index) {
                case 0 -> player.performCommand("clan info");
                case 1 -> runSafely(player, () -> { claims.claim(ownClan(player), player.getUniqueId(), ChunkPos.of(player.getChunk())); player.sendMessage("§aChunk reivindicado."); });
                case 2 -> runSafely(player, () -> { claims.unclaim(ownClan(player), player.getUniqueId(), ChunkPos.of(player.getChunk())); player.sendMessage("§eClaim removido."); });
                case 3 -> clanInviteForm(player);
                case 4 -> clanTrustMenu(player);
                case 5 -> clanCofferMenu(player);
                case 6 -> clanAllianceMenu(player);
                case 7 -> confirm(player, "Sair do clã?", "Ao sair, você perde acesso aos claims do clã. Confirma?", () -> { clans.leave(player.getUniqueId()); player.sendMessage("§eVocê saiu do clã."); });
                default -> { }
            }
        }, buttons.toArray(String[]::new));
    }

    private void clanCreateForm(Player player) {
        if (!BedrockForms.sendInput(plugin, player, "Criar clã", values -> runSafely(player, () -> {
            String name = values.get(0).trim();
            String tag = values.get(1).trim();
            if (name.isBlank() || tag.isBlank()) throw new IllegalArgumentException("Nome e tag são obrigatórios.");
            Clan clan = clans.create(player.getUniqueId(), name.replace('_', ' '), tag);
            player.sendMessage("§aClã " + clan.name() + " criado.");
        }), new BedrockForms.Input("Nome do clã", "Guardiões do Nemeton"),
                new BedrockForms.Input("Tag curta", "GN"))) {
            player.sendMessage("§eUse: /clan criar <nome> <tag>");
        }
    }

    private void clanInviteForm(Player player) {
        if (!BedrockForms.sendInput(plugin, player, "Convidar jogador", values -> runSafely(player, () -> {
            String targetName = values.get(0).trim();
            if (targetName.isBlank()) throw new IllegalArgumentException("Informe um jogador.");
            Clan clan = ownClan(player);
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
            clans.invite(clan, player.getUniqueId(), target.getUniqueId());
            player.sendMessage("§aConvite enviado para " + targetName + ".");
        }), new BedrockForms.Input("Jogador", "nome exato"))) {
            player.sendMessage("§eUse: /clan convidar <jogador>");
        }
    }

    private void clanTrustMenu(Player player) {
        BedrockForms.sendSimple(plugin, player, "Acesso ao território",
                "Líeres e vice-líderes podem permitir que uma pessoa construa e interaja nos claims sem torná-la membro do clã.",
                index -> {
                    if (index == 0) clanTrustForm(player, true);
                    else if (index == 1) clanTrustForm(player, false);
                }, "Autorizar jogador", "Remover acesso", "Fechar");
    }

    private void clanTrustForm(Player player, boolean add) {
        BedrockForms.sendInput(plugin, player, add ? "Autorizar no território" : "Remover acesso",
                values -> runSafely(player, () -> {
                    String target = values.get(0).trim();
                    if (target.isBlank()) throw new IllegalArgumentException("Informe um jogador.");
                    claims.trustClan(ownClan(player), player.getUniqueId(), Bukkit.getOfflinePlayer(target).getUniqueId(), add);
                    player.sendMessage(add ? "§aJogador autorizado no território do clã." : "§eAcesso removido.");
                }), new BedrockForms.Input("Jogador", "nome exato"));
    }

    private void clanCofferMenu(Player player) {
        BedrockForms.sendSimple(plugin, player, "Cofre do clã",
                "Defina o cofre olhando para uma magnetita dentro do claim.\nDeposite diamantes para guerras futuras.",
                index -> {
                    if (index == 0) runSafely(player, () -> coffer(player, new String[]{"cofre", "definir"}));
                    else if (index == 1) BedrockForms.sendInput(plugin, player, "Depositar diamantes",
                            values -> runSafely(player, () -> coffer(player, new String[]{"cofre", "depositar", values.get(0).trim()})),
                            new BedrockForms.Input("Quantidade", "16"));
                },
                "Definir olhando para magnetita", "Depositar diamantes", "Fechar");
    }

    private void clanAllianceMenu(Player player) {
        BedrockForms.sendSimple(plugin, player, "Alianças",
                "Aliança exige aceite bilateral. Romper gera trégua.",
                index -> {
                    if (index == 0) BedrockForms.sendInput(plugin, player, "Pedir/aceitar aliança",
                            values -> runSafely(player, () -> { Clan source = ownClan(player), target = clanTag(values.get(0).trim()); Alliance alliance = alliances.requestOrAccept(source, target, player.getUniqueId()); player.sendMessage(alliance.status() == Alliance.Status.ACTIVE ? "§aAliança firmada." : "§ePedido de aliança enviado."); }),
                            new BedrockForms.Input("Tag do clã", "TAG"));
                    else if (index == 1) BedrockForms.sendInput(plugin, player, "Romper aliança",
                            values -> confirm(player, "Romper aliança?", "Romper com [" + values.get(0).trim() + "] inicia trégua.", () -> runSafely(player, () -> { alliances.breakAlliance(ownClan(player), clanTag(values.get(0).trim()), player.getUniqueId()); player.sendMessage("§eAliança rompida; trégua iniciada."); })),
                            new BedrockForms.Input("Tag do clã", "TAG"));
                },
                "Pedir/aceitar", "Romper", "Fechar");
    }

    private boolean openSanctuaryMenu(Player player) {
        int owned = state.sanctuariesOf(player.getUniqueId()).size();
        return BedrockForms.sendSimple(plugin, player, "Santuário",
                "Seu santuário pessoal protege até 4 chunks conectados.\n\nChunks marcados: " + owned + "/4\nChunk atual: " + player.getChunk().getX() + ", " + player.getChunk().getZ(),
                index -> {
                    switch (index) {
                        case 0 -> runSafely(player, () -> { claims.sanctuary(player.getUniqueId(), ChunkPos.of(player.getChunk())); player.sendMessage("§aChunk adicionado ao santuário."); });
                        case 1 -> runSafely(player, () -> { claims.removeSanctuary(player.getUniqueId(), ChunkPos.of(player.getChunk())); player.sendMessage("§eChunk removido do santuário."); });
                        case 2 -> trustForm(player, true);
                        case 3 -> trustForm(player, false);
                        default -> { }
                    }
                },
                "Marcar/expandir chunk", "Remover chunk", "Confiar jogador", "Remover confiança", "Fechar");
    }

    private void trustForm(Player player, boolean add) {
        BedrockForms.sendInput(plugin, player, add ? "Confiar jogador" : "Remover confiança",
                values -> runSafely(player, () -> {
                    String target = values.get(0).trim();
                    if (target.isBlank()) throw new IllegalArgumentException("Informe um jogador.");
                    claims.trust(player.getUniqueId(), Bukkit.getOfflinePlayer(target).getUniqueId(), add);
                    player.sendMessage("§aConfiança atualizada.");
                }),
                new BedrockForms.Input("Jogador", "nome exato"));
    }

    private boolean openRaidMenu(Player player) {
        String content = settings.war().raidsEnabled()
                ? "Raids estão habilitadas.\nUse declarar para propor três horários."
                : "Raids e modo de guerra estão desativados neste alpha.\nVamos liberar depois de simulações seguras.";
        return BedrockForms.sendSimple(plugin, player, "Raids", content, index -> {
            if (index == 0) player.performCommand("raid status");
            else if (index == 1 && settings.war().raidsEnabled()) raidDeclareForm(player);
        }, settings.war().raidsEnabled()
                ? new String[]{"Status", "Declarar raid", "Fechar"}
                : new String[]{"Status", "Fechar"});
    }

    private void raidDeclareForm(Player player) {
        BedrockForms.sendInput(plugin, player, "Declarar raid",
                values -> runSafely(player, () -> {
                    Clan attacker = ownClan(player), defender = clanTag(values.get(0).trim());
                    int stake = Integer.parseInt(values.get(1).trim());
                    List<Instant> slots = List.of(parseSlot(values.get(2).trim()), parseSlot(values.get(3).trim()), parseSlot(values.get(4).trim()));
                    Raid created = raids.declare(attacker, defender, player.getUniqueId(), stake, slots);
                    player.sendMessage("§aRaid declarada: " + RaidService.shortId(created.id()));
                }),
                new BedrockForms.Input("Tag defensora", "TAG"),
                new BedrockForms.Input("Diamantes", "16"),
                new BedrockForms.Input("Horário 1", "2026-07-08T20:00"),
                new BedrockForms.Input("Horário 2", "2026-07-08T21:00"),
                new BedrockForms.Input("Horário 3", "2026-07-08T22:00"));
    }

    private void confirm(Player player, String title, String content, Runnable action) {
        if (!BedrockForms.sendModal(plugin, player, title, content, "Confirmar", "Cancelar", confirmed -> {
            if (confirmed) runSafely(player, action);
        })) {
            player.sendMessage("§eConfirmação indisponível no seu cliente; use o comando manual.");
        }
    }

    private void runSafely(Player player, Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException | DateTimeException exception) { player.sendMessage("§c" + exception.getMessage()); }
        catch (Exception exception) { player.sendMessage("§cOcorreu um erro interno. A administração foi avisada."); Bukkit.getLogger().severe("Nemeton form: " + exception); }
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of(); return switch (command.getName()) {
            case "clan" -> List.of("ajuda", "criar", "convidar", "aceitar", "sair", "expulsar", "promover", "claim", "unclaim", "confiar", "desconfiar", "cofre", "aliar", "romper", "acesso", "chat", "info");
            case "santuario" -> List.of("ajuda", "marcar", "expandir", "remover", "confiar", "desconfiar");
            case "raid" -> List.of("ajuda", "declarar", "agendar", "status", "premio");
            case "menu", "painel" -> List.of("clan", "santuario", "troca", "mapa", "mochila", "mods");
            case "kit" -> List.of("lascado");
            default -> List.of(); };
    }
}
