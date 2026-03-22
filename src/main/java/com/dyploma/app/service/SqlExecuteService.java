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
    /**
     * Порахувати точну кількість рядків, які повертає довільний SELECT‑запит.
     * Реалізація: обгортаємо у SELECT COUNT(*) FROM (<sql>) t
     */
    public long countRows(ConnectionDao.SavedConnection conn, String sql, int timeoutSec) throws Exception {
        if (conn == null) throw new IllegalArgumentException("No active connection");
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("SQL is empty");
        String normalized = sql.trim().toUpperCase();
        if (!(normalized.startsWith("SELECT") || normalized.startsWith("WITH "))) {
            throw new IllegalArgumentException("Only SELECT queries are allowed in chat mode");
        }
        if (timeoutSec <= 0) timeoutSec = 10;
        String type = conn.dbType == null ? "" : conn.dbType.trim().toUpperCase();
        switch (type) {
            case "SQLITE":
                // Якщо AI вже повернув агрегатний COUNT(*), виконуємо його як є і читаємо значення напряму
                if (looksLikeAggregateCount(sql)) {
                    return countAggregateSqlite(conn.dbFilePath, sql, timeoutSec);
                }
                // Інакше — стандартна обгортка SELECT COUNT(*) FROM (<sql>) t
                return countSqlite(conn.dbFilePath, sql, timeoutSec);
            default:
                throw new UnsupportedOperationException("Execution for DB type '" + conn.dbType + "' is not implemented yet");
        }
    }

    private boolean looksLikeAggregateCount(String sql) {
        if (sql == null) return false;
        String s = sql.trim().toUpperCase();
        // Дуже проста евристика: без важкого парсера шукаємо SELECT COUNT( у запиті (враховуючи можливий WITH ... SELECT COUNT()
        if (s.startsWith("SELECT COUNT(")) return true;
        if (s.startsWith("WITH ") && s.contains(" SELECT COUNT(")) return true;
        // Інколи модель додає коментарі або пробіли на початку — перевіримо наявність COUNT( у першому SELECT
        int idxSel = s.indexOf("SELECT");
        int idxCnt = s.indexOf("COUNT(");
        return idxSel >= 0 && idxCnt > idxSel && idxCnt - idxSel < 200; // COUNT близько після SELECT
    }
    private long countSqlite(String dbFilePath, String sql, int timeoutSec) throws Exception {
        if (dbFilePath == null || dbFilePath.isBlank()) {
            throw new IllegalArgumentException("SQLite file path is empty");
        }
        // Санітизація: прибираємо фінальну ";" і зайві пробіли, щоб вкладений SELECT був валідний у SQLite
        String cleaned = sql == null ? "" : sql.trim();
        if (cleaned.endsWith(";")) cleaned = cleaned.substring(0, cleaned.length()-1).trim();
        String wrapped = "SELECT COUNT(*) AS cnt FROM (" + cleaned + ") t";
        System.out.println("[DEBUG_LOG] countSqlite: starting, len=" + cleaned.length());
        DriverManager.setLoginTimeout((int) Duration.ofSeconds(timeoutSec).toSeconds());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFilePath);
             Statement st = c.createStatement()) {
            st.setQueryTimeout(timeoutSec);
            try (ResultSet rs = st.executeQuery(wrapped)) {
                if (rs.next()) {
                    long val = rs.getLong(1);
                    System.out.println("[DEBUG_LOG] countSqlite: finished, count=" + val);
                    return val;
                }
                return 0L;
            }
        } catch (Exception ex) {
            System.out.println("[DEBUG_LOG] countSqlite: FAILED - " + ex.getMessage());
            throw ex;
        }
    }

    // Виконати агрегатний SELECT COUNT(*) як є і зчитати перше значення
    private long countAggregateSqlite(String dbFilePath, String sql, int timeoutSec) throws Exception {
        if (dbFilePath == null || dbFilePath.isBlank()) {
            throw new IllegalArgumentException("SQLite file path is empty");
        }
        String cleaned = sql == null ? "" : sql.trim();
        if (cleaned.endsWith(";")) cleaned = cleaned.substring(0, cleaned.length()-1).trim();
        System.out.println("[DEBUG_LOG] countSqlite(aggregate): starting, len=" + cleaned.length());
        DriverManager.setLoginTimeout((int) Duration.ofSeconds(timeoutSec).toSeconds());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFilePath);
             Statement st = c.createStatement()) {
            st.setQueryTimeout(timeoutSec);
            try (ResultSet rs = st.executeQuery(cleaned)) {
                if (rs.next()) {
                    long val = rs.getLong(1);
                    System.out.println("[DEBUG_LOG] countSqlite(aggregate): finished, count=" + val);
                    return val;
                }
                return 0L;
            }
        } catch (Exception ex) {
            System.out.println("[DEBUG_LOG] countSqlite(aggregate): FAILED - " + ex.getMessage());
            throw ex;
        }
    }
}
