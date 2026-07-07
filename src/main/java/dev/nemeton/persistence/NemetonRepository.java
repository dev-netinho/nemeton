package dev.nemeton.persistence;

import dev.nemeton.domain.*;
import dev.nemeton.state.ServerState;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class NemetonRepository {
    private final Database database;
    public NemetonRepository(Database database) { this.database = database; }

    public ServerState load() {
        return database.transaction(connection -> {
            ServerState state = new ServerState();
            Map<UUID, Clan> clans = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM clans"); ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Clan.BlockPoint coffer = rs.getString("coffer_world") == null ? null : new Clan.BlockPoint(
                            rs.getString("coffer_world"), rs.getInt("coffer_x"), rs.getInt("coffer_y"), rs.getInt("coffer_z"));
                    Clan clan = new Clan(UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("tag"),
                            UUID.fromString(rs.getString("owner_uuid")), WarState.valueOf(rs.getString("war_state")),
                            instant(rs, "war_changed_at"), instant(rs, "war_locked_until"), rs.getInt("coffer_diamonds"), coffer);
                    clan.setDiscord(rs.getString("discord_role_id"), rs.getString("discord_text_id"), rs.getString("discord_voice_id"));
                    clans.put(clan.id(), clan);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM clan_members"); ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Clan clan = clans.get(UUID.fromString(rs.getString("clan_id")));
                    if (clan != null) clan.addMember(UUID.fromString(rs.getString("player_uuid")), ClanRole.valueOf(rs.getString("role")));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM clan_claims"); ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Clan clan = clans.get(UUID.fromString(rs.getString("clan_id")));
                    if (clan != null) clan.addClaim(new ChunkPos(rs.getString("world_name"), rs.getInt("chunk_x"), rs.getInt("chunk_z")));
                }
            }
            clans.values().forEach(state::addClan);
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM sanctuaries"); ResultSet rs = statement.executeQuery()) {
                while (rs.next()) state.addSanctuary(new ChunkPos(rs.getString("world_name"), rs.getInt("chunk_x"), rs.getInt("chunk_z")), UUID.fromString(rs.getString("owner_uuid")));
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM sanctuary_trust"); ResultSet rs = statement.executeQuery()) {
                while (rs.next()) state.trustSanctuary(UUID.fromString(rs.getString("owner_uuid")), UUID.fromString(rs.getString("trusted_uuid")));
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM clan_trust"); ResultSet rs = statement.executeQuery()) {
                while (rs.next()) state.trustClan(UUID.fromString(rs.getString("clan_id")), UUID.fromString(rs.getString("trusted_uuid")));
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM alliances"); ResultSet rs = statement.executeQuery()) {
                while (rs.next()) state.putAlliance(new Alliance(UUID.fromString(rs.getString("clan_a")), UUID.fromString(rs.getString("clan_b")), Alliance.Status.valueOf(rs.getString("status")), rs.getBoolean("access_granted"), instant(rs, "truce_until")));
            }
            loadRaids(connection, state);
            return state;
        });
    }

    private void loadRaids(Connection connection, ServerState state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM raids WHERE state NOT IN ('COMPLETED','CANCELLED')"); ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                List<Instant> slots = List.of(rs.getTimestamp("slot_one").toInstant(), rs.getTimestamp("slot_two").toInstant(), rs.getTimestamp("slot_three").toInstant());
                Integer chosen = (Integer) rs.getObject("chosen_slot");
                String winner = rs.getString("winner_id");
                Raid raid = new Raid(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("attacker_id")),
                        UUID.fromString(rs.getString("defender_id")), RaidState.valueOf(rs.getString("state")), rs.getInt("stake"), slots,
                        rs.getTimestamp("choice_deadline").toInstant(), chosen, instant(rs, "starts_at"), instant(rs, "ends_at"),
                        rs.getInt("capture_seconds"), winner == null ? null : UUID.fromString(winner));
                state.addRaid(raid);
            }
        }
    }

    public void insertClan(Clan clan) {
        database.transaction(c -> { try (PreparedStatement ps = c.prepareStatement("INSERT INTO clans(id,name,tag,owner_uuid,war_state) VALUES(?,?,?,?,?)")) {
            ps.setString(1, clan.id().toString()); ps.setString(2, clan.name()); ps.setString(3, clan.tag()); ps.setString(4, clan.owner().toString()); ps.setString(5, clan.warState().name()); ps.executeUpdate();
        } try (PreparedStatement ps = c.prepareStatement("INSERT INTO clan_members(clan_id,player_uuid,role) VALUES(?,?,?)")) {
            ps.setString(1, clan.id().toString()); ps.setString(2, clan.owner().toString()); ps.setString(3, ClanRole.LEADER.name()); ps.executeUpdate();
        } return null; });
    }
    public void deleteClan(UUID id) { update("DELETE FROM clans WHERE id=?", id.toString()); }
    public void addMember(UUID clan, UUID player, ClanRole role) { update("INSERT INTO clan_members(clan_id,player_uuid,role) VALUES(?,?,?)", clan.toString(), player.toString(), role.name()); }
    public void removeMember(UUID player) { update("DELETE FROM clan_members WHERE player_uuid=?", player.toString()); }
    public void setRole(UUID player, ClanRole role) { update("UPDATE clan_members SET role=? WHERE player_uuid=?", role.name(), player.toString()); }
    public void addClaim(UUID clan, ChunkPos pos) { update("INSERT INTO clan_claims(clan_id,world_name,chunk_x,chunk_z) VALUES(?,?,?,?)", clan.toString(), pos.world(), pos.x(), pos.z()); }
    public void removeClaim(ChunkPos pos) { update("DELETE FROM clan_claims WHERE world_name=? AND chunk_x=? AND chunk_z=?", pos.world(), pos.x(), pos.z()); }
    public void addSanctuary(UUID owner, ChunkPos pos) { update("INSERT INTO sanctuaries(owner_uuid,world_name,chunk_x,chunk_z) VALUES(?,?,?,?)", owner.toString(), pos.world(), pos.x(), pos.z()); }
    public void removeSanctuary(ChunkPos pos) { update("DELETE FROM sanctuaries WHERE world_name=? AND chunk_x=? AND chunk_z=?", pos.world(), pos.x(), pos.z()); }
    public void trustSanctuary(UUID owner, UUID trusted) { update("INSERT IGNORE INTO sanctuary_trust(owner_uuid,trusted_uuid) VALUES(?,?)", owner.toString(), trusted.toString()); }
    public void untrustSanctuary(UUID owner, UUID trusted) { update("DELETE FROM sanctuary_trust WHERE owner_uuid=? AND trusted_uuid=?", owner.toString(), trusted.toString()); }
    public void trustClan(UUID clan, UUID trusted) { update("INSERT IGNORE INTO clan_trust(clan_id,trusted_uuid) VALUES(?,?)", clan.toString(), trusted.toString()); }
    public void untrustClan(UUID clan, UUID trusted) { update("DELETE FROM clan_trust WHERE clan_id=? AND trusted_uuid=?", clan.toString(), trusted.toString()); }
    public void saveClanRuntime(Clan clan) {
        update("UPDATE clans SET war_state=?,war_changed_at=?,war_locked_until=?,coffer_diamonds=?,coffer_world=?,coffer_x=?,coffer_y=?,coffer_z=?,discord_role_id=?,discord_text_id=?,discord_voice_id=? WHERE id=?",
                clan.warState().name(), timestamp(clan.warChangedAt()), timestamp(clan.warLockedUntil()), clan.cofferDiamonds(),
                clan.coffer() == null ? null : clan.coffer().world(), clan.coffer() == null ? null : clan.coffer().x(),
                clan.coffer() == null ? null : clan.coffer().y(), clan.coffer() == null ? null : clan.coffer().z(),
                clan.discordRoleId(), clan.discordTextId(), clan.discordVoiceId(), clan.id().toString());
    }
    public void insertRaid(Raid raid) {
        update("INSERT INTO raids(id,attacker_id,defender_id,state,stake,slot_one,slot_two,slot_three,choice_deadline) VALUES(?,?,?,?,?,?,?,?,?)",
                raid.id().toString(), raid.attackerId().toString(), raid.defenderId().toString(), raid.state().name(), raid.stake(),
                timestamp(raid.slots().get(0)), timestamp(raid.slots().get(1)), timestamp(raid.slots().get(2)), timestamp(raid.choiceDeadline()));
    }
    public void reserveRaid(Raid raid) {
        database.transaction(connection -> {
            try (PreparedStatement reserve = connection.prepareStatement("UPDATE clans SET coffer_diamonds=coffer_diamonds-? WHERE id=? AND coffer_diamonds>=?")) {
                for (UUID clan : List.of(raid.attackerId(), raid.defenderId())) {
                    reserve.setInt(1, raid.stake()); reserve.setString(2, clan.toString()); reserve.setInt(3, raid.stake());
                    if (reserve.executeUpdate() != 1) throw new IllegalStateException("Saldo do cofre mudou durante a declaração.");
                }
            }
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO raids(id,attacker_id,defender_id,state,stake,slot_one,slot_two,slot_three,choice_deadline) VALUES(?,?,?,?,?,?,?,?,?)")) {
                Object[] values = {raid.id().toString(), raid.attackerId().toString(), raid.defenderId().toString(), raid.state().name(), raid.stake(), timestamp(raid.slots().get(0)), timestamp(raid.slots().get(1)), timestamp(raid.slots().get(2)), timestamp(raid.choiceDeadline())};
                for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]); ps.executeUpdate();
            }
            return null;
        });
    }
    public void settleRaid(Raid raid, UUID winner, int prize) {
        database.transaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("UPDATE clans SET coffer_diamonds=coffer_diamonds+? WHERE id=?")) { ps.setInt(1, prize); ps.setString(2, winner.toString()); ps.executeUpdate(); }
            try (PreparedStatement ps = connection.prepareStatement("UPDATE raids SET state='COMPLETED',winner_id=?,capture_seconds=? WHERE id=? AND state='RESTORING'")) {
                ps.setString(1, winner.toString()); ps.setInt(2, raid.captureSeconds()); ps.setString(3, raid.id().toString()); if (ps.executeUpdate() != 1) throw new IllegalStateException("Raid já liquidada.");
            }
            return null;
        });
    }
    public void cancelRaidWithPayouts(Raid raid, int attackerAmount, int defenderAmount) {
        database.transaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("UPDATE clans SET coffer_diamonds=coffer_diamonds+? WHERE id=?")) {
                if (attackerAmount > 0) { ps.setInt(1, attackerAmount); ps.setString(2, raid.attackerId().toString()); ps.executeUpdate(); }
                if (defenderAmount > 0) { ps.setInt(1, defenderAmount); ps.setString(2, raid.defenderId().toString()); ps.executeUpdate(); }
            }
            try (PreparedStatement ps = connection.prepareStatement("UPDATE raids SET state='CANCELLED' WHERE id=? AND state NOT IN ('COMPLETED','CANCELLED')")) {
                ps.setString(1, raid.id().toString()); if (ps.executeUpdate() != 1) throw new IllegalStateException("Raid já liquidada.");
            }
            return null;
        });
    }
    public void saveRaid(Raid raid) {
        update("UPDATE raids SET state=?,chosen_slot=?,starts_at=?,ends_at=?,capture_seconds=?,winner_id=? WHERE id=?",
                raid.state().name(), raid.chosenSlot(), timestamp(raid.startsAt()), timestamp(raid.endsAt()), raid.captureSeconds(),
                raid.winnerId() == null ? null : raid.winnerId().toString(), raid.id().toString());
    }
    public void saveAlliance(Alliance alliance) {
        update("INSERT INTO alliances(clan_a,clan_b,status,access_granted,truce_until) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status),access_granted=VALUES(access_granted),truce_until=VALUES(truce_until)",
                alliance.clanA().toString(), alliance.clanB().toString(), alliance.status().name(), alliance.accessGranted(), timestamp(alliance.truceUntil()));
    }
    public void recordBlock(UUID raid, String world, int x, int y, int z, String blockData) {
        update("INSERT IGNORE INTO raid_block_changes(raid_id,world_name,block_x,block_y,block_z,original_block_data) VALUES(?,?,?,?,?,?)",
                raid.toString(), world, x, y, z, blockData);
    }
    public List<BlockChange> blockChanges(UUID raid) {
        return database.transaction(c -> { List<BlockChange> result = new ArrayList<>(); try (PreparedStatement ps = c.prepareStatement("SELECT * FROM raid_block_changes WHERE raid_id=?")) {
            ps.setString(1, raid.toString()); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(new BlockChange(rs.getString("world_name"), rs.getInt("block_x"), rs.getInt("block_y"), rs.getInt("block_z"), rs.getString("original_block_data"))); }
        } return result; });
    }
    public void reward(UUID player, int diamonds, String reason) { update("INSERT INTO pending_rewards(player_uuid,material,amount,reason) VALUES(?,'DIAMOND',?,?)", player.toString(), diamonds, reason); }

    private void update(String sql, Object... values) {
        database.transaction(connection -> { try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
            ps.executeUpdate();
        } return null; });
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException { Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    public record BlockChange(String world, int x, int y, int z, String blockData) {}
}
