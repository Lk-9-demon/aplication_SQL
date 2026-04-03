package com.dyploma.app.service;

import com.dyploma.app.dao.ConnectionDao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

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

    public QueryResult querySample(ConnectionDao.SavedConnection conn,
                                   String sql,
                                   int maxRows,
                                   int timeoutSec) throws Exception {
        if (conn == null) throw new IllegalArgumentException("No active connection");
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("SQL is empty");

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

        try (Connection c = openConnection(conn, timeoutSec);
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
                    if (rows.size() >= maxRows) {
                        truncated = true;
                        break;
                    }
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

    public long countRows(ConnectionDao.SavedConnection conn, String sql, int timeoutSec) throws Exception {
        if (conn == null) throw new IllegalArgumentException("No active connection");
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("SQL is empty");

        String normalized = sql.trim().toUpperCase();
        if (!(normalized.startsWith("SELECT") || normalized.startsWith("WITH "))) {
            throw new IllegalArgumentException("Only SELECT queries are allowed in chat mode");
        }
        if (timeoutSec <= 0) timeoutSec = 10;

        if (looksLikeAggregateCount(sql)) {
            return countAggregate(conn, sql, timeoutSec);
        }
        return countWrapped(conn, sql, timeoutSec);
    }

    private Connection openConnection(ConnectionDao.SavedConnection conn, int timeoutSec) throws Exception {
        if (conn == null) throw new IllegalArgumentException("No active connection");

        DriverManager.setLoginTimeout((int) Duration.ofSeconds(timeoutSec).toSeconds());

        String type = conn.dbType == null ? "" : conn.dbType.trim().toUpperCase();
        return switch (type) {
            case "SQLITE" -> {
                if (conn.dbFilePath == null || conn.dbFilePath.isBlank()) {
                    throw new IllegalArgumentException("SQLite file path is empty");
                }
                yield DriverManager.getConnection("jdbc:sqlite:" + conn.dbFilePath);
            }
            case "MYSQL" -> DriverManager.getConnection(buildJdbcUrl(conn), buildProperties(conn));
            case "POSTGRES", "POSTGRESQL" -> DriverManager.getConnection(buildJdbcUrl(conn), buildProperties(conn));
            default -> throw new UnsupportedOperationException("Execution for DB type '" + conn.dbType + "' is not implemented yet");
        };
    }

    private String buildJdbcUrl(ConnectionDao.SavedConnection conn) {
        String type = conn.dbType == null ? "" : conn.dbType.trim().toUpperCase();
        return switch (type) {
            case "MYSQL" -> "jdbc:mysql://" + conn.host + ":" + conn.port + "/" + conn.database
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            case "POSTGRES", "POSTGRESQL" -> "jdbc:postgresql://" + conn.host + ":" + conn.port + "/" + conn.database;
            case "SQLITE" -> "jdbc:sqlite:" + conn.dbFilePath;
            default -> throw new UnsupportedOperationException("Execution for DB type '" + conn.dbType + "' is not implemented yet");
        };
    }

    private Properties buildProperties(ConnectionDao.SavedConnection conn) {
        Properties props = new Properties();
        if (conn.username != null) props.setProperty("user", conn.username);
        if (conn.password != null) props.setProperty("password", conn.password);
        return props;
    }

    private boolean looksLikeAggregateCount(String sql) {
        if (sql == null) return false;
        String s = sql.trim().toUpperCase();
        if (s.startsWith("SELECT COUNT(")) return true;
        if (s.startsWith("WITH ") && s.contains(" SELECT COUNT(")) return true;
        int idxSel = s.indexOf("SELECT");
        int idxCnt = s.indexOf("COUNT(");
        return idxSel >= 0 && idxCnt > idxSel && idxCnt - idxSel < 200;
    }

    private long countWrapped(ConnectionDao.SavedConnection conn, String sql, int timeoutSec) throws Exception {
        String cleaned = sanitizeSql(sql);
        String wrapped = "SELECT COUNT(*) AS cnt FROM (" + cleaned + ") t";
        System.out.println("[DEBUG_LOG] countQuery: starting, len=" + cleaned.length());
        try (Connection c = openConnection(conn, timeoutSec);
             Statement st = c.createStatement()) {
            st.setQueryTimeout(timeoutSec);
            try (ResultSet rs = st.executeQuery(wrapped)) {
                if (rs.next()) {
                    long val = rs.getLong(1);
                    System.out.println("[DEBUG_LOG] countQuery: finished, count=" + val);
                    return val;
                }
                return 0L;
            }
        } catch (Exception ex) {
            System.out.println("[DEBUG_LOG] countQuery: FAILED - " + ex.getMessage());
            throw ex;
        }
    }

    private long countAggregate(ConnectionDao.SavedConnection conn, String sql, int timeoutSec) throws Exception {
        String cleaned = sanitizeSql(sql);
        System.out.println("[DEBUG_LOG] countAggregate: starting, len=" + cleaned.length());
        try (Connection c = openConnection(conn, timeoutSec);
             Statement st = c.createStatement()) {
            st.setQueryTimeout(timeoutSec);
            try (ResultSet rs = st.executeQuery(cleaned)) {
                if (rs.next()) {
                    long val = rs.getLong(1);
                    System.out.println("[DEBUG_LOG] countAggregate: finished, count=" + val);
                    return val;
                }
                return 0L;
            }
        } catch (Exception ex) {
            System.out.println("[DEBUG_LOG] countAggregate: FAILED - " + ex.getMessage());
            throw ex;
        }
    }

    private String sanitizeSql(String sql) {
        String cleaned = sql == null ? "" : sql.trim();
        if (cleaned.endsWith(";")) cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        return cleaned;
    }
}
