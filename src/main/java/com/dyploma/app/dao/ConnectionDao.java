package com.dyploma.app.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;

public class ConnectionDao {

    public void insertConnection(
            long userId,
            String name,
            String dbType,
            String host,
            int port,
            String databaseName,
            String dbUsername,
            String dbPassword,
            String dbFilePath
    ) {
        String sql = """
            INSERT INTO db_connections
            (user_id, name, db_type, host, port, database_name, db_username, db_password, db_file_path)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
            """;

        try (Connection conn = com.dyploma.app.dao.LocalDb.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, userId);
            ps.setString(2, name);
            ps.setString(3, dbType);
            ps.setString(4, host);
            ps.setInt(5, port);
            ps.setString(6, databaseName);
            ps.setString(7, dbUsername);
            ps.setString(8, dbPassword);
            ps.setString(9, dbFilePath);

            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("Insert connection failed: " + e.getMessage(), e);
        }
    }

    /**
     * Перевіряє, чи існує хоч один збережений профіль підключення для вказаного користувача.
     */
    public boolean hasAnyForUser(long userId) {
        String sql = "SELECT 1 FROM db_connections WHERE user_id = ? LIMIT 1";
        try (Connection conn = com.dyploma.app.dao.LocalDb.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new RuntimeException("Check connections failed: " + e.getMessage(), e);
        }
    }

    /**
     * Повертає будь-який (останній за id) збережений профіль для користувача,
     * щоб відобразити як "активний" на Dashboard.
     */
    public SavedConnection findAnyForUser(long userId) {
        String sql = """
            SELECT id, name, db_type, host, port, database_name, db_username, db_file_path
            FROM db_connections
            WHERE user_id = ?
            ORDER BY id DESC
            LIMIT 1
        """;
        try (Connection conn = com.dyploma.app.dao.LocalDb.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new SavedConnection(
                            rs.getLong("id"),
                            rs.getString("name"),
                            rs.getString("db_type"),
                            rs.getString("host"),
                            rs.getInt("port"),
                            rs.getString("database_name"),
                            rs.getString("db_username"),
                            rs.getString("db_file_path")
                    );
                }
                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException("Load connection failed: " + e.getMessage(), e);
        }
    }

    /**
     * DTO для короткого опису збереженого підключення (без пароля).
     */
    public static class SavedConnection {
        public final long id;
        public final String name;
        public final String dbType;
        public final String host;
        public final int port;
        public final String database;
        public final String username;
        public final String dbFilePath;

        public SavedConnection(long id, String name, String dbType, String host, int port, String database, String username, String dbFilePath) {
            this.id = id;
            this.name = name;
            this.dbType = dbType;
            this.host = host;
            this.port = port;
            this.database = database;
            this.username = username;
            this.dbFilePath = dbFilePath;
        }
    }
}
