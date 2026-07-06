package dev.nemeton.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.nemeton.config.Settings;
import org.flywaydb.core.Flyway;

import java.sql.*;
import java.util.function.Function;

public final class Database implements AutoCloseable {
    private final HikariDataSource dataSource;

    public Database(Settings.Database settings) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(settings.jdbcUrl());
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
    }

    public Connection connection() throws SQLException { return dataSource.getConnection(); }

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

