package com.dyploma.app.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;

public class DbTestService {

    public void testConnection(String dbType,
                               String host,
                               int port,
                               String databaseName,
                               String username,
                               String password) throws SQLException {

        String url = buildJdbcUrl(dbType, host, port, databaseName);

        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);

        // таймаут на підключення (секунди)
        DriverManager.setLoginTimeout((int) Duration.ofSeconds(5).toSeconds());

        try (Connection conn = DriverManager.getConnection(url, props)) {
            // якщо сюди дійшло — підключення успішне
            if (conn == null || conn.isClosed()) {
                throw new SQLException("Connection is closed");
            }
        }
    }

    private String buildJdbcUrl(String dbType, String host, int port, String db) {
        String t = dbType == null ? "" : dbType.trim().toUpperCase();

        return switch (t) {
            case "POSTGRES", "POSTGRESQL" -> "jdbc:postgresql://" + host + ":" + port + "/" + db;
            case "MYSQL" -> "jdbc:mysql://" + host + ":" + port + "/" + db
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            default -> throw new IllegalArgumentException("Unsupported DB type: " + dbType);
        };
    }
}
