package com.dyploma.app.service;

import com.dyploma.app.dao.ConnectionDao;

import java.sql.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Безпечне локальне виконання SQL: тайм-аут, ліміт рядків, базове блокування небезпечних команд.
 * Перший інкремент: підтримка SQLITE (файлова БД). Для MySQL/Postgres потребуємо зчитувати пароль —
 * додамо в одному з наступних кроків.
 */
public class SqlExecuteService {

    public static class QueryResult {
        public final List<String> columns;
        public final List<List<Object>> rows;
        public final boolean truncated;

        public QueryResult(List<String> columns, List<List<Object>> rows, boolean truncated) {
            this.columns = columns;
            this.rows = rows;
            this.truncated = truncated;
        }
    }

    /**
     * Виконати SELECT‑подібний SQL і повернути обмежений семпл результату.
     * - Забороняє потенційно небезпечні команди (DDL/трансакційні/модифікації даних)
     * - Підтримка лише SQLITE на цьому етапі
     */
    public QueryResult querySample(ConnectionDao.SavedConnection conn,
                                   String sql,
                                   int maxRows,
                                   int timeoutSec) throws Exception {
        if (conn == null) throw new IllegalArgumentException("No active connection");
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("SQL is empty");

        // Базова перевірка безпеки: дозволяємо тільки SELECT/with CTE
        String normalized = sql.trim().toUpperCase();
        if (!(normalized.startsWith("SELECT") || normalized.startsWith("WITH "))) {
            throw new IllegalArgumentException("Only SELECT queries are allowed in chat mode");
        }
        if (normalized.contains(" DROP ") || normalized.contains(" TRUNCATE ") ||
            normalized.contains(" ALTER ") || normalized.contains(" DELETE ") ||
            normalized.contains(" UPDATE ") || normalized.contains(" INSERT ")) {
            throw new IllegalArgumentException("Dangerous statements are not allowed in chat mode");
        }

        if (maxRows <= 0) maxRows = 10;
        if (timeoutSec <= 0) timeoutSec = 10;

        String type = conn.dbType == null ? "" : conn.dbType.trim().toUpperCase();
        switch (type) {
            case "SQLITE":
                return querySqlite(conn.dbFilePath, sql, maxRows, timeoutSec);
            default:
                throw new UnsupportedOperationException("Execution for DB type '" + conn.dbType + "' is not implemented yet");
        }
    }

    private QueryResult querySqlite(String dbFilePath, String sql, int maxRows, int timeoutSec) throws Exception {
        if (dbFilePath == null || dbFilePath.isBlank()) {
            throw new IllegalArgumentException("SQLite file path is empty");
        }
        DriverManager.setLoginTimeout((int) Duration.ofSeconds(timeoutSec).toSeconds());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFilePath);
             Statement st = c.createStatement()) {
            st.setQueryTimeout(timeoutSec);
            try (ResultSet rs = st.executeQuery(sql)) {
                List<String> cols = new ArrayList<>();
                ResultSetMetaData md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    cols.add(md.getColumnLabel(i));
                }

                List<List<Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() >= maxRows) { truncated = true; break; }
                    List<Object> row = new ArrayList<>(cols.size());
                    for (int i = 1; i <= cols.size(); i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }
                return new QueryResult(cols, rows, truncated);
            }
        }
    }
}
