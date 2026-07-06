package dev.nemeton.persistence;

import dev.nemeton.config.Settings;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.ResultSet;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class DatabaseIntegrationTest {
    @Container static final MariaDBContainer<?> MARIA = new MariaDBContainer<>("mariadb:11.7");

    @Test void migratesTheCompleteSchema() throws Exception {
        Settings.Database settings = new Settings.Database(MARIA.getHost(), MARIA.getMappedPort(3306), MARIA.getDatabaseName(), MARIA.getUsername(), MARIA.getPassword(), 2);
        try (Database database = new Database(settings)) {
            database.migrate();
            try (var connection = database.connection(); var statement = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?")) {
                statement.setString(1, MARIA.getDatabaseName()); try (ResultSet result = statement.executeQuery()) { result.next(); assertThat(result.getInt(1)).isGreaterThanOrEqualTo(10); }
            }
        }
    }
}
