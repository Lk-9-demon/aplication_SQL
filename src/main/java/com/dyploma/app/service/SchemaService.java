package com.dyploma.app.service;

import com.dyploma.app.dao.ConnectionDao;
import com.dyploma.app.ui.AppState;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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

        System.out.println("[DEBUG_LOG] SchemaService.refresh: dialect=" + dialect + ", url=" + jdbcUrl);
        long t0 = System.currentTimeMillis();
        try (Connection c = createConnection(jdbcUrl, conn)) {
            DatabaseMetaData md = c.getMetaData();
            SchemaInfo schema = new SchemaInfo();
            schema.dialect = dialect;
            schema.generatedAt = Instant.now().toEpochMilli();

            int tablesCount = 0;
            int columnsCount = 0;
            try (ResultSet rsTables = md.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rsTables.next()) {
                    String tableName = rsTables.getString("TABLE_NAME");
                    TableInfo t = new TableInfo();
                    t.name = tableName;

                    try (ResultSet rsCols = md.getColumns(null, null, tableName, "%")) {
                        while (rsCols.next()) {
                            ColumnInfo col = new ColumnInfo();
                            col.name = rsCols.getString("COLUMN_NAME");
                            col.type = rsCols.getString("TYPE_NAME");
                            int nullable = rsCols.getInt("NULLABLE");
                            col.nullable = (nullable == DatabaseMetaData.columnNullable);
                            t.columns.add(col);
                            columnsCount++;
                        }
                    }

                    try (ResultSet rsPk = md.getPrimaryKeys(null, null, tableName)) {
                        while (rsPk.next()) {
                            t.primaryKey.add(rsPk.getString("COLUMN_NAME"));
                        }
                    }

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
                    tablesCount++;
                }
            }

            System.out.println("[DEBUG_LOG] SchemaService.refresh: tables=" + tablesCount + ", columns=" + columnsCount);

            AppState.setCurrentSchema(schema);

            Path out = writeSchemaToFile(userId, conn, schema);
            try {
                long size = Files.size(out);
                System.out.println("[DEBUG_LOG] SchemaService.refresh: JSON saved to " + out + ", size=" + size + " bytes");
            } catch (Exception ignore) {
            }

            long took = System.currentTimeMillis() - t0;
            System.out.println("[DEBUG_LOG] SchemaService.refresh: finished in " + took + " ms");

            return schema;
        }
    }

    private Connection createConnection(String url, ConnectionDao.SavedConnection conn) throws Exception {
        if (url.startsWith("jdbc:sqlite:")) {
            return DriverManager.getConnection(url);
        }
        java.util.Properties props = new java.util.Properties();
        if (conn.username != null) props.setProperty("user", conn.username);
        if (conn.password != null) props.setProperty("password", conn.password);
        return DriverManager.getConnection(url, props);
    }

    private Path writeSchemaToFile(long userId, ConnectionDao.SavedConnection conn, SchemaInfo schema) throws Exception {
        String safeName = (conn.name == null || conn.name.isBlank()) ? ("conn_" + conn.id) : conn.name.replaceAll("[^a-zA-Z0-9_-]", "_");
        Path dir = Path.of(System.getProperty("user.home"), ".dyploma", "metadata", String.valueOf(userId));
        Files.createDirectories(dir);
        Path file = dir.resolve(safeName + ".json");
        try (FileWriter fw = new FileWriter(file.toFile())) {
            gson.toJson(schema, fw);
        }
        return file;
    }
}
