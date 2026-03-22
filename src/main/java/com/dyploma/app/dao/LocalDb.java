package com.dyploma.app.dao;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class LocalDb {
    private static final String DB_NAME = "app.db";

    public static Connection getConnection() {
        try {
            Path dir = Path.of(System.getProperty("user.home"), ".dyploma");
            Files.createDirectories(dir);
            String url = "jdbc:sqlite:" + dir.resolve(DB_NAME);
            System.out.println(url);
            Connection conn = DriverManager.getConnection(url);
            initSchema(conn);
            return conn;
        } catch (Exception e) {
            throw new RuntimeException("Local DB init failed: " + e.getMessage(), e);
        }
    }

    private static void initSchema(Connection conn) {
        String usersSql = """
        CREATE TABLE IF NOT EXISTS users (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          username TEXT NOT NULL UNIQUE,
          password_hash TEXT NOT NULL,
          created_at TEXT NOT NULL DEFAULT (datetime('now'))
        );
        """;

        String connectionsSql = """
        CREATE TABLE IF NOT EXISTS db_connections (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          user_id INTEGER NOT NULL,
          name TEXT NOT NULL,
          db_type TEXT NOT NULL,
          host TEXT NOT NULL,
          port INTEGER NOT NULL,
          database_name TEXT NOT NULL,
          db_username TEXT NOT NULL,
          db_password TEXT NOT NULL,
          -- шлях до локальної SQLite-бази (опційно)
          db_file_path TEXT,
          created_at TEXT NOT NULL DEFAULT (datetime('now')),
          updated_at TEXT NOT NULL DEFAULT (datetime('now')),
          FOREIGN KEY(user_id) REFERENCES users(id)
        );
        """;

        try (Statement st = conn.createStatement()) {
            st.execute(usersSql);
            st.execute(connectionsSql);

            // Міграція: якщо стара таблиця без db_file_path — додати колонку.
            boolean hasDbFilePath = false;
            try (var rs = st.executeQuery("PRAGMA table_info(db_connections)")) {
                while (rs.next()) {
                    if ("db_file_path".equalsIgnoreCase(rs.getString("name"))) {
                        hasDbFilePath = true;
                        break;
                    }
                }
            }
            if (!hasDbFilePath) {
                try {
                    st.execute("ALTER TABLE db_connections ADD COLUMN db_file_path TEXT");
                } catch (Exception ignore) {
                    // якщо колонка вже існує або інша помилка — пропускаємо
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Schema init failed: " + e.getMessage(), e);
        }
    }

}
