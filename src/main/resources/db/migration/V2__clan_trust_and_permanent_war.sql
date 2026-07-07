CREATE TABLE IF NOT EXISTS clan_trust (
  clan_id CHAR(36) NOT NULL,
  trusted_uuid CHAR(36) NOT NULL,
  PRIMARY KEY (clan_id, trusted_uuid),
  CONSTRAINT fk_clan_trust FOREIGN KEY (clan_id) REFERENCES clans(id) ON DELETE CASCADE
);

UPDATE clans
SET war_state = 'ACTIVE',
    war_changed_at = COALESCE(war_changed_at, CURRENT_TIMESTAMP(3)),
    war_locked_until = NULL;
