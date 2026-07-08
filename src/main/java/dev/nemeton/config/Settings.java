package dev.nemeton.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Settings {
    private static final Pattern ENV = Pattern.compile("^\\$\\{([A-Z0-9_]+):([^}]*)}$");
    private final Database database;
    private volatile Hub hub;
    private final Claims claims;
    private final War war;
    private final Discord discord;

    public Settings(Database database, Hub hub, Claims claims, War war, Discord discord) {
        this.database = database;
        this.hub = hub;
        this.claims = claims;
        this.war = war;
        this.discord = discord;
    }

    public static Settings load(FileConfiguration config) {
        Database database = new Database(
                env(config.getString("database.host", "database")),
                config.getInt("database.port", 3306),
                env(config.getString("database.name", "nemeton")),
                env(config.getString("database.username", "nemeton")),
                env(config.getString("database.password", "change-me")),
                config.getInt("database.pool-size", 6));
        Hub hub = new Hub(config.getString("nemeton.world", "world"), config.getDouble("nemeton.x", .5),
                config.getDouble("nemeton.y", 80), config.getDouble("nemeton.z", .5),
                (float) config.getDouble("nemeton.yaw", 0), (float) config.getDouble("nemeton.pitch", 0),
                config.getDouble("nemeton.center-x", config.getDouble("nemeton.x", .5)),
                config.getDouble("nemeton.center-z", config.getDouble("nemeton.z", .5)),
                config.getInt("nemeton.radius", 128),
                Duration.ofSeconds(config.getLong("nemeton.teleport-warmup-seconds", 3)),
                Duration.ofMinutes(config.getLong("nemeton.teleport-cooldown-minutes", 5)));
        Claims claims = new Claims(config.getInt("claims.sanctuary-limit", 4), config.getInt("claims.clan-base", 6),
                config.getInt("claims.clan-per-member", 4), config.getInt("claims.clan-maximum", 50),
                config.getInt("claims.war-bonus-percent", 25));
        War war = new War(config.getBoolean("war.raids-enabled", false),
                Duration.ofHours(config.getLong("war.activation-hours", 72)),
                Duration.ofDays(config.getLong("war.minimum-active-days", 7)),
                Duration.ofHours(config.getLong("war.truce-hours", 72)),
                Duration.ofHours(config.getLong("war.declaration-minimum-hours", 24)),
                Duration.ofHours(config.getLong("war.declaration-maximum-hours", 72)),
                Duration.ofHours(config.getLong("war.defender-choice-hours", 12)),
                Duration.ofMinutes(config.getLong("war.duration-minutes", 60)),
                config.getInt("war.capture-seconds", 180), config.getInt("war.death-lock-seconds", 60),
                config.getInt("war.minimum-team-size", 2), config.getInt("war.maximum-team-size", 6),
                config.getInt("war.minimum-stake", 16), config.getInt("war.maximum-stake", 64));
        Discord discord = new Discord(config.getBoolean("discord.enabled", false), env(config.getString("discord.guild-id", "")),
                firstNonBlank(System.getenv("DISCORD_BOT_TOKEN"), env(config.getString("discord.bot-token", ""))),
                env(config.getString("discord.clans-category-id", "")), env(config.getString("discord.alerts-channel-id", "")),
                env(config.getString("discord.approved-role-id", "")),
                env(config.getString("discord.clan-leader-role-id", "")),
                env(config.getString("discord.clan-officer-role-id", "")),
                env(config.getString("discord.clan-member-role-id", "")),
                env(config.getString("discord.leaders-channel-id", "")),
                env(config.getString("discord.recruitment-channel-id", "")),
                env(config.getString("discord.bot-user-id", "")),
                Duration.ofMinutes(config.getLong("discord.intrusion-cooldown-minutes", 10)));
        return new Settings(database, hub, claims, war, discord);
    }

    private static String env(String value) {
        if (value == null) return "";
        Matcher matcher = ENV.matcher(value);
        if (!matcher.matches()) return value;
        return System.getenv().getOrDefault(matcher.group(1), matcher.group(2));
    }
    private static String firstNonBlank(String first, String second) { return first != null && !first.isBlank() ? first : second; }

    public Database database() { return database; }
    public Hub hub() { return hub; }
    public Claims claims() { return claims; }
    public War war() { return war; }
    public Discord discord() { return discord; }
    public void updateHub(Hub hub) { this.hub = hub; }

    public record Database(String host, int port, String name, String username, String password, int poolSize) {
        public String jdbcUrl() { return "jdbc:mariadb://" + host + ":" + port + "/" + name + "?useUnicode=true&characterEncoding=utf8"; }
    }
    public record Hub(String world, double x, double y, double z, float yaw, float pitch,
                      double centerX, double centerZ, int radius,
                      Duration warmup, Duration cooldown) {}
    public record Claims(int sanctuaryLimit, int clanBase, int clanPerMember, int clanMaximum, int warBonusPercent) {}
    public record War(boolean raidsEnabled, Duration activation, Duration minimumActive, Duration truce, Duration declarationMinimum,
                      Duration declarationMaximum, Duration choiceWindow, Duration duration, int captureSeconds,
                      int deathLockSeconds, int minimumTeam, int maximumTeam, int minimumStake, int maximumStake) {}
    public record Discord(boolean enabled, String guildId, String botToken, String clansCategoryId,
                          String alertsChannelId, String approvedRoleId,
                          String clanLeaderRoleId, String clanOfficerRoleId, String clanMemberRoleId,
                          String leadersChannelId, String recruitmentChannelId, String botUserId,
                          Duration intrusionCooldown) {}
}
