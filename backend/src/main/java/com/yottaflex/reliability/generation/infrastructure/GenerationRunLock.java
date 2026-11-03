package com.yottaflex.reliability.generation.infrastructure;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

import org.springframework.stereotype.Component;

@Component
public class GenerationRunLock {

    private static final long LOCK_KEY = 6_438_151_362_930_952_280L;
    private final DataSource dataSource;

    public GenerationRunLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Lease tryAcquire() throws SQLException {
        Connection connection = dataSource.getConnection();
        boolean acquired = false;
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet resultSet = statement.executeQuery()) {
                acquired = resultSet.next() && resultSet.getBoolean(1);
            }
        } finally {
            if (!acquired) {
                connection.close();
            }
        }
        return acquired ? new Lease(connection) : null;
    }

    public static final class Lease implements AutoCloseable {

        private final Connection connection;
        private boolean closed;

        private Lease(Connection connection) {
            this.connection = connection;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                statement.setLong(1, LOCK_KEY);
                statement.execute();
            } catch (SQLException ignored) {
                try {
                    connection.close();
                } catch (SQLException closeFailure) {
                    ignored.addSuppressed(closeFailure);
                }
            } finally {
                try {
                    connection.close();
                } catch (SQLException ignored) { }
            }
        }
    }
}
