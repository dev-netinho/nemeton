package dev.nemeton.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.nemeton.config.Settings;
import org.flywaydb.core.Flyway;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.function.Function;

public final class Database implements AutoCloseable {
    private final HikariDataSource dataSource;

    public Database(Settings.Database settings) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(settings.jdbcUrl());
        config.setDriverClassName("org.mariadb.jdbc.Driver");
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.poolSize());
        config.setMinimumIdle(1);
        config.setPoolName("NemetonDB");
        config.setConnectionTimeout(10_000);
        this.dataSource = new HikariDataSource(config);
    }

    public void migrate() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        if (!tableExists("clans")) runInitialSchema();
    }

    public Connection connection() throws SQLException { return dataSource.getConnection(); }

    private boolean tableExists(String table) {
        try (Connection connection = connection();
             ResultSet result = connection.getMetaData().getTables(connection.getCatalog(), null, table, new String[]{"TABLE"})) {
            return result.next();
        } catch (SQLException exception) {
            throw new PersistenceException(exception);
        }
    }

    private void runInitialSchema() {
        try (var input = Database.class.getResourceAsStream("/db/migration/V1__initial_schema.sql")) {
            if (input == null) throw new IllegalStateException("Migration V1__initial_schema.sql não encontrada no JAR.");
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                for (String raw : sql.split(";")) {
                    String command = raw.strip();
                    if (!command.isBlank()) statement.execute(command);
                }
            }
        } catch (Exception exception) {
            throw new PersistenceException(exception);
        }
    }

    public <T> T transaction(SqlFunction<Connection, T> work) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                throw new PersistenceException(exception);
            }
        } catch (SQLException exception) {
            throw new PersistenceException(exception);
        }
    }

    @Override public void close() { dataSource.close(); }

    @FunctionalInterface public interface SqlFunction<T, R> { R apply(T value) throws Exception; }
    public static final class PersistenceException extends RuntimeException { public PersistenceException(Throwable cause) { super(cause); } }
}
