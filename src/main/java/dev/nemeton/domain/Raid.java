package dev.nemeton.domain;

import java.time.Instant;
import java.util.*;

public final class Raid {
    private final UUID id;
    private final UUID attackerId;
    private final UUID defenderId;
    private RaidState state;
    private final int stake;
    private final List<Instant> slots;
    private final Instant choiceDeadline;
    private Integer chosenSlot;
    private Instant startsAt;
    private Instant endsAt;
    private int captureSeconds;
    private UUID winnerId;
    private final Map<UUID, Side> participants = new HashMap<>();

    public Raid(UUID id, UUID attackerId, UUID defenderId, int stake, List<Instant> slots, Instant choiceDeadline) {
        this(id, attackerId, defenderId, RaidState.DECLARED, stake, slots, choiceDeadline, null, null, null, 0, null);
    }

    public Raid(UUID id, UUID attackerId, UUID defenderId, RaidState state, int stake, List<Instant> slots,
                Instant choiceDeadline, Integer chosenSlot, Instant startsAt, Instant endsAt,
                int captureSeconds, UUID winnerId) {
        if (slots.size() != 3) throw new IllegalArgumentException("Uma raid precisa de três horários.");
        this.id = id; this.attackerId = attackerId; this.defenderId = defenderId; this.state = state;
        this.stake = stake; this.slots = List.copyOf(slots); this.choiceDeadline = choiceDeadline;
        this.chosenSlot = chosenSlot; this.startsAt = startsAt; this.endsAt = endsAt;
        this.captureSeconds = captureSeconds; this.winnerId = winnerId;
    }

    public UUID id() { return id; }
    public UUID attackerId() { return attackerId; }
    public UUID defenderId() { return defenderId; }
    public RaidState state() { return state; }
    public int stake() { return stake; }
    public List<Instant> slots() { return slots; }
    public Instant choiceDeadline() { return choiceDeadline; }
    public Integer chosenSlot() { return chosenSlot; }
    public Instant startsAt() { return startsAt; }
    public Instant endsAt() { return endsAt; }
    public int captureSeconds() { return captureSeconds; }
    public UUID winnerId() { return winnerId; }
    public Map<UUID, Side> participants() { return Collections.unmodifiableMap(participants); }

    public void schedule(int oneBasedSlot, Instant endsAt) {
        if (oneBasedSlot < 1 || oneBasedSlot > 3) throw new IllegalArgumentException("Horário inválido.");
        this.chosenSlot = oneBasedSlot;
        this.startsAt = slots.get(oneBasedSlot - 1);
        this.endsAt = endsAt;
        this.state = RaidState.SCHEDULED;
    }
    public void start(Collection<UUID> attackers, Collection<UUID> defenders) {
        state = RaidState.ACTIVE;
        attackers.forEach(id -> participants.put(id, Side.ATTACKER));
        defenders.forEach(id -> participants.put(id, Side.DEFENDER));
    }
    public void captureTick(boolean uncontested) { captureSeconds = uncontested ? captureSeconds + 1 : 0; }
    public void beginRestore() { state = RaidState.RESTORING; }
    public void recover() { state = RaidState.RECOVERY; }
    public void complete(UUID winner) { state = RaidState.COMPLETED; winnerId = winner; }
    public void cancel() { state = RaidState.CANCELLED; }

    public enum Side { ATTACKER, DEFENDER }
}

