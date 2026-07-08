package dev.nemeton.service;

import dev.nemeton.integration.BedrockForms;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public final class TradeService implements Listener, TabExecutor {
    private static final Duration INVITE_TTL = Duration.ofMinutes(2);
    private static final int[] OWN_SLOTS = {0, 1, 2, 9, 10, 11, 18, 19, 20, 27, 28, 29, 36, 37, 38};
    private static final int[] OTHER_SLOTS = {6, 7, 8, 15, 16, 17, 24, 25, 26, 33, 34, 35, 42, 43, 44};
    private static final Set<Integer> OWN_SLOT_SET = Arrays.stream(OWN_SLOTS).collect(HashSet::new, Set::add, Set::addAll);
    private static final int ACCEPT_SLOT = 49;
    private static final int CANCEL_SLOT = 53;

    private final JavaPlugin plugin;
    private final Map<UUID, Invite> invites = new HashMap<>();
    private final Map<UUID, TradeSession> sessionsById = new HashMap<>();
    private final Map<UUID, TradeSession> sessionsByPlayer = new HashMap<>();
    private boolean shuttingDown;

    public TradeService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Comando disponível apenas em jogo.");
            return true;
        }
        try {
            if (args.length == 0 || args[0].equalsIgnoreCase("abrir")) {
                openExisting(player);
                return true;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "aceitar" -> accept(player, args);
                case "oferecer", "offer" -> offerHeld(player, args);
                case "limpar", "clear" -> clearOffer(player);
                case "ver", "status" -> showStatus(player);
                case "cancelar" -> cancel(player);
                case "ajuda" -> usage(player);
                default -> invite(player, args[0]);
            }
        } catch (IllegalArgumentException exception) {
            player.sendMessage("§c" + exception.getMessage());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        List<String> result = new ArrayList<>(List.of("aceitar", "abrir", "oferecer", "limpar", "ver", "cancelar", "ajuda"));
        Bukkit.getOnlinePlayers().stream().map(Player::getName).forEach(result::add);
        return result;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof TradeHolder holder)) return;
        TradeSession session = sessionsById.get(holder.sessionId());
        Player player = (Player) event.getWhoClicked();
        if (session == null) {
            event.setCancelled(true);
            return;
        }
        if (session.bedrockSafe) {
            event.setCancelled(true);
            if (!openBedrockTradeMenu(player, session)) {
                player.sendMessage("§eEsta troca envolve Bedrock. Use §f/troca abrir§e para ver o painel seguro.");
            }
            return;
        }
        int raw = event.getRawSlot();
        if (event.isShiftClick()) {
            event.setCancelled(true);
            player.sendMessage("§eShift-click fica bloqueado na troca para evitar item voando. Clique normal no seu lado.");
            return;
        }
        if (raw >= event.getView().getTopInventory().getSize()) return;
        if (raw == ACCEPT_SLOT) {
            event.setCancelled(true);
            readOffer(session, player.getUniqueId(), event.getInventory());
            session.setAccepted(player.getUniqueId(), true);
            sync(session);
            if (session.leftAccepted && session.rightAccepted) complete(session);
            return;
        }
        if (raw == CANCEL_SLOT) {
            event.setCancelled(true);
            cancel(session, "Troca cancelada.");
            return;
        }
        if (!OWN_SLOT_SET.contains(raw)) {
            event.setCancelled(true);
            return;
        }
        session.leftAccepted = false;
        session.rightAccepted = false;
        Bukkit.getScheduler().runTask(plugin, () -> {
            readOffer(session, player.getUniqueId(), event.getInventory());
            sync(session);
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof TradeHolder holder)) return;
        TradeSession session = sessionsById.get(holder.sessionId());
        if (session == null) {
            event.setCancelled(true);
            return;
        }
        if (session.bedrockSafe) {
            event.setCancelled(true);
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < topSize && !OWN_SLOT_SET.contains(slot)) {
                event.setCancelled(true);
                return;
            }
        }
        Player player = (Player) event.getWhoClicked();
        session.leftAccepted = false;
        session.rightAccepted = false;
        Bukkit.getScheduler().runTask(plugin, () -> {
            readOffer(session, player.getUniqueId(), event.getInventory());
            sync(session);
        });
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof TradeHolder holder)) return;
        TradeSession session = sessionsById.get(holder.sessionId());
        if (session == null) return;
        readOffer(session, event.getPlayer().getUniqueId(), event.getInventory());
        session.openInventories.remove(event.getPlayer().getUniqueId());
        if (!shuttingDown && sessionsById.containsKey(session.id)) {
            event.getPlayer().sendMessage("§7Troca pausada. Use §f/troca abrir§7 para voltar ou §f/troca cancelar§7 para desistir.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        TradeSession session = sessionsByPlayer.get(event.getPlayer().getUniqueId());
        if (session != null) cancel(session, "Troca cancelada porque um jogador desconectou.");
        invites.remove(event.getPlayer().getUniqueId());
        invites.entrySet().removeIf(entry -> entry.getValue().source().equals(event.getPlayer().getUniqueId()));
    }

    public void shutdown() {
        shuttingDown = true;
        for (TradeSession session : new ArrayList<>(sessionsById.values())) {
            cancel(session, "Troca cancelada pelo reinício do servidor.");
        }
    }

    private void invite(Player player, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) throw new IllegalArgumentException("Jogador não encontrado online.");
        if (target.equals(player)) throw new IllegalArgumentException("Você não pode trocar consigo mesmo.");
        if (sessionsByPlayer.containsKey(player.getUniqueId())) throw new IllegalArgumentException("Você já tem uma troca aberta.");
        if (sessionsByPlayer.containsKey(target.getUniqueId())) throw new IllegalArgumentException("Esse jogador já tem uma troca aberta.");
        invites.put(target.getUniqueId(), new Invite(player.getUniqueId(), Instant.now().plus(INVITE_TTL)));
        player.sendMessage("§aPedido de troca enviado para " + target.getName() + ".");
        target.sendMessage("§6" + player.getName() + " quer trocar com você. §f/troca aceitar " + player.getName());
        if (BedrockForms.isBedrock(target)) {
            BedrockForms.sendSimple(plugin, target, "Pedido de troca",
                    player.getName() + " quer trocar com você.",
                    index -> {
                        if (index == 0) {
                            try { acceptInvite(target, new String[]{"aceitar", player.getName()}); }
                            catch (IllegalArgumentException exception) { target.sendMessage("§c" + exception.getMessage()); }
                        }
                    },
                    "Aceitar", "Agora não");
        }
    }

    private void openExisting(Player player) {
        TradeSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session == null) {
            if (openBedrockInviteMenu(player)) return;
            usage(player);
            return;
        }
        if (session.bedrockSafe) {
            showStatus(player);
            return;
        }
        open(player, session);
    }

    private boolean openBedrockInviteMenu(Player player) {
        if (!BedrockForms.isBedrock(player)) return false;
        List<Player> players = Bukkit.getOnlinePlayers().stream()
                .filter(other -> !other.equals(player))
                .filter(other -> !sessionsByPlayer.containsKey(other.getUniqueId()))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (players.isEmpty()) {
            return BedrockForms.sendSimple(plugin, player, "Troca", "Não há outro jogador livre para trocar agora.",
                    ignored -> {}, "Fechar");
        }
        String[] buttons = new String[players.size() + 1];
        for (int i = 0; i < players.size(); i++) buttons[i] = "Trocar com " + players.get(i).getName();
        buttons[buttons.length - 1] = "Fechar";
        return BedrockForms.sendSimple(plugin, player, "Iniciar troca",
                "Escolha um jogador online. Depois vocês podem fechar e reabrir a tela sem perder a oferta.",
                index -> {
                    if (index >= 0 && index < players.size()) {
                        try { invite(player, players.get(index).getName()); }
                        catch (IllegalArgumentException exception) { player.sendMessage("§c" + exception.getMessage()); }
                    }
                }, buttons);
    }

    private void cancel(Player player) {
        TradeSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session == null) throw new IllegalArgumentException("Você não tem troca aberta.");
        cancel(session, "Troca cancelada.");
    }

    private void open(Player player, TradeSession session) {
        if (session.bedrockSafe) {
            showStatus(player);
            return;
        }
        Player other = Bukkit.getPlayer(session.other(player.getUniqueId()));
        String title = "Troca com " + (other == null ? "jogador" : other.getName());
        Inventory inventory = Bukkit.createInventory(new TradeHolder(session.id, player.getUniqueId()), 54, title);
        session.openInventories.put(player.getUniqueId(), inventory);
        render(session, player.getUniqueId(), inventory);
        player.openInventory(inventory);
    }

    private void render(TradeSession session, UUID viewer, Inventory inventory) {
        ItemStack[] own = session.offerOf(viewer);
        ItemStack[] other = session.offerOf(session.other(viewer));
        inventory.clear();
        ItemStack divider = item(Material.GRAY_STAINED_GLASS_PANE, "§8");
        for (int slot : List.of(3, 4, 5, 12, 13, 14, 21, 22, 23, 30, 31, 32, 39, 40, 41, 45, 46, 47, 48, 50, 51, 52)) inventory.setItem(slot, divider);
        for (int i = 0; i < OWN_SLOTS.length; i++) inventory.setItem(OWN_SLOTS[i], cloneOrNull(own[i]));
        for (int i = 0; i < OTHER_SLOTS.length; i++) inventory.setItem(OTHER_SLOTS[i], cloneOrNull(other[i]));
        inventory.setItem(4, item(Material.PAPER, "§fSeu lado à esquerda", "§7Feche e reabra quando quiser."));
        inventory.setItem(13, item(Material.PAPER, "§fOutro jogador à direita", "§7Itens da direita são só visualização."));
        boolean accepted = session.accepted(viewer);
        boolean otherAccepted = session.accepted(session.other(viewer));
        inventory.setItem(ACCEPT_SLOT, item(accepted ? Material.LIME_DYE : Material.GRAY_DYE,
                accepted ? "§aVocê aceitou" : "§eClique para aceitar",
                otherAccepted ? "§aO outro jogador já aceitou." : "§7Aguardando o outro jogador."));
        inventory.setItem(CANCEL_SLOT, item(Material.BARRIER, "§cCancelar troca"));
    }

    private void sync(TradeSession session) {
        for (Map.Entry<UUID, Inventory> entry : session.openInventories.entrySet()) {
            render(session, entry.getKey(), entry.getValue());
        }
    }

    private void readOffer(TradeSession session, UUID player, Inventory inventory) {
        ItemStack[] offer = session.offerOf(player);
        for (int i = 0; i < OWN_SLOTS.length; i++) {
            offer[i] = cloneOrNull(inventory.getItem(OWN_SLOTS[i]));
        }
    }

    private void complete(TradeSession session) {
        Player left = Bukkit.getPlayer(session.left);
        Player right = Bukkit.getPlayer(session.right);
        if (left == null || right == null) {
            cancel(session, "Troca cancelada: jogador offline.");
            return;
        }
        closeAndForget(session);
        give(left, session.rightOffer);
        give(right, session.leftOffer);
        left.sendMessage("§aTroca concluída.");
        right.sendMessage("§aTroca concluída.");
    }

    private void cancel(TradeSession session, String message) {
        Player left = Bukkit.getPlayer(session.left);
        Player right = Bukkit.getPlayer(session.right);
        closeAndForget(session);
        if (left != null) {
            give(left, session.leftOffer);
            left.sendMessage("§e" + message);
        }
        if (right != null) {
            give(right, session.rightOffer);
            right.sendMessage("§e" + message);
        }
    }

    private void closeAndForget(TradeSession session) {
        sessionsById.remove(session.id);
        sessionsByPlayer.remove(session.left);
        sessionsByPlayer.remove(session.right);
        for (UUID viewer : new ArrayList<>(session.openInventories.keySet())) {
            Player player = Bukkit.getPlayer(viewer);
            if (player != null && player.getOpenInventory().getTopInventory().getHolder() instanceof TradeHolder holder && holder.sessionId().equals(session.id)) {
                player.closeInventory();
            }
        }
        session.openInventories.clear();
    }

    private void give(Player player, ItemStack[] items) {
        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR) continue;
            player.getInventory().addItem(item.clone()).values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
        Arrays.fill(items, null);
    }

    private void usage(Player player) {
        if (BedrockForms.isBedrock(player) && BedrockForms.sendSimple(plugin, player, "Troca",
                "A troca no Bedrock usa uma tela nativa.\n\n"
                        + "1. Escolha um jogador.\n"
                        + "2. Segure o item que quer oferecer.\n"
                        + "3. Toque em \"Oferecer item da mão\".\n"
                        + "4. Revise as duas ofertas e aceite quando estiver certo.\n\n"
                        + "Você pode fechar a tela para pegar outro item e abrir de novo com /troca.",
                index -> {
                    if (index == 0) openExisting(player);
                    else if (index == 1) showStatus(player);
                },
                "Iniciar/abrir troca", "Ver troca atual", "Fechar")) {
            return;
        }
        player.sendMessage("§6/troca <jogador> §7— chama alguém para trocar.");
        player.sendMessage("§6/troca aceitar [jogador] §7— aceita convite.");
        player.sendMessage("§6/troca abrir §7— reabre a troca pausada.");
        player.sendMessage("§6/troca oferecer [qtd] §7— modo Bedrock: oferece o item da mão.");
        player.sendMessage("§6/troca ver §7— modo Bedrock: mostra ofertas.");
        player.sendMessage("§6/troca limpar §7— modo Bedrock: recolhe sua oferta.");
        player.sendMessage("§6/troca cancelar §7— devolve os itens e encerra.");
    }

    private void accept(Player player, String[] args) {
        TradeSession active = sessionsByPlayer.get(player.getUniqueId());
        if (active != null && active.bedrockSafe && args.length == 1) {
            acceptSafeOffer(player, active);
            return;
        }
        acceptInvite(player, args);
    }

    private void acceptInvite(Player player, String[] args) {
        Invite invite = invites.get(player.getUniqueId());
        if (invite == null || invite.expires().isBefore(Instant.now())) {
            invites.remove(player.getUniqueId());
            throw new IllegalArgumentException("Você não tem convite de troca ativo.");
        }
        Player source = Bukkit.getPlayer(invite.source());
        if (source == null || !source.isOnline()) throw new IllegalArgumentException("Quem chamou a troca não está online.");
        if (args.length >= 2 && !source.getName().equalsIgnoreCase(args[1])) throw new IllegalArgumentException("Seu convite ativo é de " + source.getName() + ".");
        if (sessionsByPlayer.containsKey(player.getUniqueId()) || sessionsByPlayer.containsKey(source.getUniqueId())) throw new IllegalArgumentException("Um dos jogadores já está em troca.");
        invites.remove(player.getUniqueId());
        boolean bedrockSafe = isBedrock(player) || isBedrock(source);
        TradeSession session = new TradeSession(UUID.randomUUID(), source.getUniqueId(), player.getUniqueId(), bedrockSafe);
        sessionsById.put(session.id, session);
        sessionsByPlayer.put(source.getUniqueId(), session);
        sessionsByPlayer.put(player.getUniqueId(), session);
        if (bedrockSafe) {
            source.sendMessage("§aTroca iniciada em modo Bedrock seguro com " + player.getName() + ".");
            player.sendMessage("§aTroca iniciada em modo Bedrock seguro com " + source.getName() + ".");
            showStatus(source);
            showStatus(player);
        } else {
            open(source, session);
            open(player, session);
        }
    }

    private void offerHeld(Player player, String[] args) {
        TradeSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session == null) throw new IllegalArgumentException("Você não tem troca aberta.");
        if (!session.bedrockSafe) throw new IllegalArgumentException("No Java, coloque os itens na interface da troca.");
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) throw new IllegalArgumentException("Segure o item que deseja oferecer.");
        int amount = hand.getAmount();
        if (args.length >= 2 && !args[1].equalsIgnoreCase("all") && !args[1].equalsIgnoreCase("mao")) {
            amount = Math.max(1, Math.min(hand.getAmount(), Integer.parseInt(args[1])));
        }
        ItemStack[] offer = session.offerOf(player.getUniqueId());
        int slot = firstEmpty(offer);
        if (slot < 0) throw new IllegalArgumentException("Sua oferta já está cheia. Limpe sua oferta para recomeçar.");
        ItemStack item = hand.clone();
        item.setAmount(amount);
        offer[slot] = item;
        hand.setAmount(hand.getAmount() - amount);
        if (hand.getAmount() <= 0) player.getInventory().setItemInMainHand(null);
        session.leftAccepted = false;
        session.rightAccepted = false;
        broadcastSafe(session, "§eOferta atualizada. Ambos precisam aceitar novamente.");
        refreshSafe(session);
    }

    private void clearOffer(Player player) {
        TradeSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session == null) throw new IllegalArgumentException("Você não tem troca aberta.");
        if (!session.bedrockSafe) throw new IllegalArgumentException("No Java, remova os itens pela interface.");
        give(player, session.offerOf(player.getUniqueId()));
        session.leftAccepted = false;
        session.rightAccepted = false;
        broadcastSafe(session, "§eOferta de " + player.getName() + " foi limpa.");
        refreshSafe(session);
    }

    private void acceptSafeOffer(Player player, TradeSession session) {
        session.setAccepted(player.getUniqueId(), true);
        broadcastSafe(session, "§a" + player.getName() + " aceitou a troca.");
        Player other = Bukkit.getPlayer(session.other(player.getUniqueId()));
        if (other != null && !session.accepted(other.getUniqueId()) && !BedrockForms.isBedrock(other)) {
            other.sendMessage("§7Use §f/troca aceitar§7 se a oferta estiver certa.");
        }
        if (session.leftAccepted && session.rightAccepted) {
            complete(session);
            return;
        }
        refreshSafe(session);
    }

    private void showStatus(Player player) {
        TradeSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session == null) {
            usage(player);
            return;
        }
        Player other = Bukkit.getPlayer(session.other(player.getUniqueId()));
        if (session.bedrockSafe && openBedrockTradeMenu(player, session)) {
            return;
        }
        player.sendMessage("§8§m                                                ");
        player.sendMessage("§6Troca com " + (other == null ? "jogador" : other.getName()) + (session.bedrockSafe ? " §7(modo Bedrock seguro)" : ""));
        player.sendMessage("§eSua oferta: §f" + describe(session.offerOf(player.getUniqueId())));
        player.sendMessage("§bOferta do outro: §f" + describe(session.offerOf(session.other(player.getUniqueId()))));
        player.sendMessage("§7Aceites: você " + yesNo(session.accepted(player.getUniqueId())) + "§7, outro " + yesNo(session.accepted(session.other(player.getUniqueId()))));
        if (session.bedrockSafe) player.sendMessage("§7Comandos: §f/troca oferecer [qtd]§7, §f/troca limpar§7, §f/troca aceitar§7, §f/troca cancelar§7.");
        player.sendMessage("§8§m                                                ");
    }

    private boolean openBedrockTradeMenu(Player player, TradeSession session) {
        if (!session.bedrockSafe || !BedrockForms.isBedrock(player)) return false;
        Player other = Bukkit.getPlayer(session.other(player.getUniqueId()));
        String otherName = other == null ? "jogador" : other.getName();
        String hand = describeHand(player);
        String content = "Negociação segura com " + otherName + ".\n\n"
                + "Sua oferta:\n" + describeLines(session.offerOf(player.getUniqueId())) + "\n\n"
                + "Oferta do outro:\n" + describeLines(session.offerOf(session.other(player.getUniqueId()))) + "\n\n"
                + "Aceites: você " + plainYesNo(session.accepted(player.getUniqueId()))
                + " • outro " + plainYesNo(session.accepted(session.other(player.getUniqueId()))) + "\n\n"
                + "Item na mão: " + hand + "\n"
                + "Você pode fechar esta tela, pegar outro item no inventário e abrir de novo com /troca.";
        return BedrockForms.sendSimple(plugin, player,
                "Troca • " + otherName,
                content,
                index -> handleBedrockTradeButton(player, index),
                "📦 Oferecer item da mão",
                "✅ Aceitar troca",
                "♻ Limpar minha oferta",
                "🔄 Atualizar tela",
                "❌ Cancelar troca",
                "Fechar");
    }

    private void refreshSafe(TradeSession session) {
        Player left = Bukkit.getPlayer(session.left);
        Player right = Bukkit.getPlayer(session.right);
        if (left != null) showStatus(left);
        if (right != null) showStatus(right);
    }

    private void handleBedrockTradeButton(Player player, int index) {
        try {
            switch (index) {
                case 0 -> offerHeld(player, new String[]{"oferecer", "all"});
                case 1 -> {
                    TradeSession session = sessionsByPlayer.get(player.getUniqueId());
                    if (session == null) throw new IllegalArgumentException("Você não tem troca aberta.");
                    acceptSafeOffer(player, session);
                }
                case 2 -> clearOffer(player);
                case 3 -> showStatus(player);
                case 4 -> cancel(player);
                default -> { }
            }
        } catch (IllegalArgumentException exception) {
            player.sendMessage("§c" + exception.getMessage());
            TradeSession session = sessionsByPlayer.get(player.getUniqueId());
            if (session != null) Bukkit.getScheduler().runTaskLater(plugin, () -> openBedrockTradeMenu(player, session), 10L);
        }
    }

    private int firstEmpty(ItemStack[] items) {
        for (int i = 0; i < items.length; i++) if (items[i] == null || items[i].getType() == Material.AIR) return i;
        return -1;
    }

    private String describe(ItemStack[] items) {
        List<String> parts = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR) continue;
            parts.add(item.getAmount() + "x " + pretty(item.getType()));
        }
        return parts.isEmpty() ? "nada" : String.join(", ", parts);
    }

    private String describeLines(ItemStack[] items) {
        List<String> parts = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR) continue;
            parts.add("• " + item.getAmount() + "x " + pretty(item.getType()));
        }
        return parts.isEmpty() ? "• nada ainda" : String.join("\n", parts);
    }

    private String describeHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) return "mão vazia";
        return hand.getAmount() + "x " + pretty(hand.getType());
    }

    private String pretty(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private String yesNo(boolean value) {
        return value ? "§asim" : "§cnão";
    }

    private String plainYesNo(boolean value) {
        return value ? "sim" : "não";
    }

    private void broadcastSafe(TradeSession session, String message) {
        Player left = Bukkit.getPlayer(session.left);
        Player right = Bukkit.getPlayer(session.right);
        if (left != null) left.sendMessage(message);
        if (right != null) right.sendMessage(message);
    }

    private boolean isBedrock(Player player) {
        return BedrockForms.isBedrock(player);
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        if (lore.length > 0) meta.lore(Arrays.stream(lore).map(Component::text).toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack cloneOrNull(ItemStack item) {
        return item == null || item.getType() == Material.AIR ? null : item.clone();
    }

    private record Invite(UUID source, Instant expires) {}
    private record TradeHolder(UUID sessionId, UUID viewer) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private static final class TradeSession {
        private final UUID id;
        private final UUID left;
        private final UUID right;
        private final ItemStack[] leftOffer = new ItemStack[OWN_SLOTS.length];
        private final ItemStack[] rightOffer = new ItemStack[OWN_SLOTS.length];
        private final Map<UUID, Inventory> openInventories = new HashMap<>();
        private final boolean bedrockSafe;
        private boolean leftAccepted;
        private boolean rightAccepted;

        private TradeSession(UUID id, UUID left, UUID right, boolean bedrockSafe) {
            this.id = id;
            this.left = left;
            this.right = right;
            this.bedrockSafe = bedrockSafe;
        }

        private UUID other(UUID player) {
            return player.equals(left) ? right : left;
        }

        private ItemStack[] offerOf(UUID player) {
            return player.equals(left) ? leftOffer : rightOffer;
        }

        private boolean accepted(UUID player) {
            return player.equals(left) ? leftAccepted : rightAccepted;
        }

        private void setAccepted(UUID player, boolean accepted) {
            if (player.equals(left)) leftAccepted = accepted;
            else rightAccepted = accepted;
        }
    }
}
