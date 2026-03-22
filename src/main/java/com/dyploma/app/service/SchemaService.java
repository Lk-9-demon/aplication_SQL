package com.dyploma.app.service;

import com.dyploma.app.dao.ConnectionDao;
import com.dyploma.app.ui.AppState;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Сервіс для витягу та збереження метаданих БД (схеми) у файл і в пам'ять (AppState).
 */
public class SchemaService {

    public static class ColumnInfo {
        public String name;
        public String type;
        public boolean nullable;
    }

    public static class ForeignKeyInfo {
        public String fkName;
        public String pkTable;
        public String fkColumn;
        public String pkColumn;
    }

    public static class TableInfo {
        public String name;
        public List<ColumnInfo> columns = new ArrayList<>();
        public List<String> primaryKey = new ArrayList<>();
        public List<ForeignKeyInfo> foreignKeys = new ArrayList<>();
    }

    public static class SchemaInfo {
        public String dialect;
        public long generatedAt;
        public List<TableInfo> tables = new ArrayList<>();
    }

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Оновити (витягнути) схему для вказаного збереженого підключення і записати у файл + AppState.
     */
    public SchemaInfo refresh(long userId, ConnectionDao.SavedConnection conn) throws Exception {
        if (conn == null) throw new IllegalArgumentException("Connection is null");

        String dialect = (conn.dbType == null) ? "" : conn.dbType.trim().toLowerCase();
        String jdbcUrl;
        if ("sqlite".equals(dialect)) {
            if (conn.dbFilePath == null || conn.dbFilePath.isBlank()) {
                throw new IllegalArgumentException("SQLite path is empty");
            }
            jdbcUrl = "jdbc:sqlite:" + conn.dbFilePath;
        } else if ("mysql".equals(dialect)) {
            jdbcUrl = "jdbc:mysql://" + conn.host + ":" + conn.port + "/" + conn.database
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        } else if ("postgres".equals(dialect) || "postgresql".equals(dialect)) {
            jdbcUrl = "jdbc:postgresql://" + conn.host + ":" + conn.port + "/" + conn.database;
        } else {
            throw new IllegalArgumentException("Unsupported dialect: " + conn.dbType);
        }

        try (Connection c = createConnection(jdbcUrl, conn)) {
            DatabaseMetaData md = c.getMetaData();
            SchemaInfo schema = new SchemaInfo();
            schema.dialect = dialect;
            schema.generatedAt = Instant.now().toEpochMilli();

            // Таблиці (тільки тип TABLE)
            try (ResultSet rsTables = md.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rsTables.next()) {
                    String tableName = rsTables.getString("TABLE_NAME");
                    TableInfo t = new TableInfo();
                    t.name = tableName;

                    // Колонки
                    try (ResultSet rsCols = md.getColumns(null, null, tableName, "%")) {
                        while (rsCols.next()) {
                            ColumnInfo col = new ColumnInfo();
                            col.name = rsCols.getString("COLUMN_NAME");
                            col.type = rsCols.getString("TYPE_NAME");
                            int nullable = rsCols.getInt("NULLABLE");
                            col.nullable = (nullable == DatabaseMetaData.columnNullable);
                            t.columns.add(col);
                        }
                    }

                    // Первинний ключ
                    try (ResultSet rsPk = md.getPrimaryKeys(null, null, tableName)) {
                        while (rsPk.next()) {
                            t.primaryKey.add(rsPk.getString("COLUMN_NAME"));
                        }
                    }

                    // Зовнішні ключі
                    try (ResultSet rsFk = md.getImportedKeys(null, null, tableName)) {
                        while (rsFk.next()) {
                            ForeignKeyInfo fk = new ForeignKeyInfo();
                            fk.fkName = rsFk.getString("FK_NAME");
                            fk.pkTable = rsFk.getString("PKTABLE_NAME");
                            fk.fkColumn = rsFk.getString("FKCOLUMN_NAME");
                            fk.pkColumn = rsFk.getString("PKCOLUMN_NAME");
                            t.foreignKeys.add(fk);
                        }
                    }

                    schema.tables.add(t);
                }
            }

            // Зберегти в AppState (простий підхід: у dedicated статичне поле через рефлексію ми не ліземо — додамо хелпер у AppState)
            setSchemaInAppState(schema);

            // Зберегти у файл JSON
            writeSchemaToFile(userId, conn, schema);

            return schema;
        }
    }

    private Connection createConnection(String url, ConnectionDao.SavedConnection conn) throws Exception {
        if (url.startsWith("jdbc:sqlite:")) {
            return DriverManager.getConnection(url);
        }
        java.util.Properties props = new java.util.Properties();
        if (conn.username != null) props.setProperty("user", conn.username);
        if (conn.dbType != null && !"sqlite".equalsIgnoreCase(conn.dbType)) {
            // пароль актуальний лише для mysql/postgres
            // пароля у DTO немає (безпека), тому очікуємо, що для тестів/демо може бути порожній
        }
        // Пробуємо без пароля (якщо БД дозволяє) — надалі розширимо за потреби
        return DriverManager.getConnection(url, props);
    }

    private void writeSchemaToFile(long userId, ConnectionDao.SavedConnection conn, SchemaInfo schema) throws Exception {
        String safeName = (conn.name == null || conn.name.isBlank()) ? ("conn_" + conn.id) : conn.name.replaceAll("[^a-zA-Z0-9_-]", "_");
        Path dir = Path.of(System.getProperty("user.home"), ".dyploma", "metadata", String.valueOf(userId));
        Files.createDirectories(dir);
        Path file = dir.resolve(safeName + ".json");
        try (FileWriter fw = new FileWriter(file.toFile())) {
            gson.toJson(schema, fw);
        }
    }

    // Невеликий місток до AppState, щоб не ламати існуючу структуру класу
    private void setSchemaInAppState(SchemaInfo schema) {
        try {
            // Спробуємо викликати метод через рефлексію, якщо він існує в майбутньому.
            AppState.class.getMethod("setCurrentSchema", Object.class).invoke(null, schema);
        } catch (Exception ignore) {
            // Якщо методу ще немає — ігноруємо. Пізніше додамо явну підтримку в AppState.
        }
    }
}
