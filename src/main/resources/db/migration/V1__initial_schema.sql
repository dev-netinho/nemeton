CREATE TABLE clans (
  id CHAR(36) PRIMARY KEY,
  name VARCHAR(32) NOT NULL,
  tag VARCHAR(8) NOT NULL UNIQUE,
  owner_uuid CHAR(36) NOT NULL,
  war_state VARCHAR(16) NOT NULL DEFAULT 'PEACEFUL',
  war_changed_at TIMESTAMP(3) NULL,
  war_locked_until TIMESTAMP(3) NULL,
  coffer_diamonds INT NOT NULL DEFAULT 0,
  coffer_world VARCHAR(64) NULL,
  coffer_x INT NULL, coffer_y INT NULL, coffer_z INT NULL,
  discord_role_id VARCHAR(32) NULL,
  discord_text_id VARCHAR(32) NULL,
  discord_voice_id VARCHAR(32) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE clan_members (
  clan_id CHAR(36) NOT NULL,
  player_uuid CHAR(36) NOT NULL UNIQUE,
  role VARCHAR(16) NOT NULL,
  joined_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (clan_id, player_uuid),
  CONSTRAINT fk_member_clan FOREIGN KEY (clan_id) REFERENCES clans(id) ON DELETE CASCADE
);

CREATE TABLE clan_claims (
  clan_id CHAR(36) NOT NULL,
  world_name VARCHAR(64) NOT NULL,
  chunk_x INT NOT NULL,
  chunk_z INT NOT NULL,
  PRIMARY KEY (world_name, chunk_x, chunk_z),
  CONSTRAINT fk_claim_clan FOREIGN KEY (clan_id) REFERENCES clans(id) ON DELETE CASCADE
);

CREATE TABLE sanctuaries (
  owner_uuid CHAR(36) NOT NULL,
  world_name VARCHAR(64) NOT NULL,
  chunk_x INT NOT NULL,
  chunk_z INT NOT NULL,
  PRIMARY KEY (world_name, chunk_x, chunk_z)
);

CREATE TABLE sanctuary_trust (
  owner_uuid CHAR(36) NOT NULL,
  trusted_uuid CHAR(36) NOT NULL,
  PRIMARY KEY (owner_uuid, trusted_uuid)
);

CREATE TABLE alliances (
  clan_a CHAR(36) NOT NULL,
  clan_b CHAR(36) NOT NULL,
  status VARCHAR(16) NOT NULL,
  access_granted BOOLEAN NOT NULL DEFAULT FALSE,
  truce_until TIMESTAMP(3) NULL,
  PRIMARY KEY (clan_a, clan_b),
  CONSTRAINT fk_alliance_a FOREIGN KEY (clan_a) REFERENCES clans(id) ON DELETE CASCADE,
  CONSTRAINT fk_alliance_b FOREIGN KEY (clan_b) REFERENCES clans(id) ON DELETE CASCADE
);

CREATE TABLE raids (
  id CHAR(36) PRIMARY KEY,
  attacker_id CHAR(36) NOT NULL,
  defender_id CHAR(36) NOT NULL,
  state VARCHAR(20) NOT NULL,
  stake INT NOT NULL,
  slot_one TIMESTAMP(3) NOT NULL,
  slot_two TIMESTAMP(3) NOT NULL,
  slot_three TIMESTAMP(3) NOT NULL,
  chosen_slot TINYINT NULL,
  choice_deadline TIMESTAMP(3) NOT NULL,
  starts_at TIMESTAMP(3) NULL,
  ends_at TIMESTAMP(3) NULL,
  capture_seconds INT NOT NULL DEFAULT 0,
  winner_id CHAR(36) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_raid_attacker FOREIGN KEY (attacker_id) REFERENCES clans(id),
  CONSTRAINT fk_raid_defender FOREIGN KEY (defender_id) REFERENCES clans(id)
);

CREATE TABLE raid_participants (
  raid_id CHAR(36) NOT NULL,
  player_uuid CHAR(36) NOT NULL,
  side VARCHAR(12) NOT NULL,
  locked_until TIMESTAMP(3) NULL,
  PRIMARY KEY (raid_id, player_uuid),
  CONSTRAINT fk_participant_raid FOREIGN KEY (raid_id) REFERENCES raids(id) ON DELETE CASCADE
);

CREATE TABLE raid_block_changes (
  raid_id CHAR(36) NOT NULL,
  world_name VARCHAR(64) NOT NULL,
  block_x INT NOT NULL, block_y INT NOT NULL, block_z INT NOT NULL,
  original_block_data TEXT NOT NULL,
  PRIMARY KEY (raid_id, world_name, block_x, block_y, block_z),
  CONSTRAINT fk_change_raid FOREIGN KEY (raid_id) REFERENCES raids(id) ON DELETE CASCADE
);

CREATE TABLE pending_rewards (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  player_uuid CHAR(36) NOT NULL,
  material VARCHAR(64) NOT NULL,
  amount INT NOT NULL,
  reason VARCHAR(128) NOT NULL,
  claimed_at TIMESTAMP(3) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE INDEX idx_raids_state_time ON raids(state, starts_at);
CREATE INDEX idx_rewards_player ON pending_rewards(player_uuid, claimed_at);
