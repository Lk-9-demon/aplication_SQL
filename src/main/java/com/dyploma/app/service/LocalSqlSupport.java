package com.dyploma.app.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalSqlSupport {

    private static final Set<String> SQL_KEYWORDS = Set.of(
            "select", "with", "from", "join", "left", "right", "inner", "outer", "full",
            "cross", "where", "group", "by", "order", "limit", "on", "as", "and", "or",
            "not", "exists", "in", "is", "null", "count", "sum", "avg", "min", "max",
            "distinct", "over", "partition", "having", "case", "when", "then", "end",
            "asc", "desc", "union", "all", "like", "between", "cast", "float", "integer",
            "double", "real", "text", "date", "timestamp", "top", "offset", "fetch"
    );

    private static final Set<String> SQL_FUNCTIONS = Set.of(
            "strftime", "date", "datetime", "julianday", "time", "round", "coalesce",
            "ifnull", "nullif", "lower", "upper", "abs", "substr", "substring",
            "length", "trim", "ltrim", "rtrim", "replace", "printf", "concat",
            "date_trunc", "extract", "sum", "avg", "min", "max", "count", "cast"
    );

    private LocalSqlSupport() {
    }

    public static String buildSchemaDdl(SchemaService.SchemaInfo schema) {
        if (schema == null) return "";

        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE statements for the database schema:\n\n");
        for (var table : schema.tables) {
            ddl.append("CREATE TABLE ").append(table.name).append(" (\n");
            for (int i = 0; i < table.columns.size(); i++) {
                var column = table.columns.get(i);
                ddl.append("  ").append(column.name).append(" ").append(mapType(column.type));
                if (i + 1 < table.columns.size()) ddl.append(",");
                ddl.append("\n");
            }
            ddl.append(");\n\n");
            if (!table.primaryKey.isEmpty()) {
                ddl.append("-- Primary key: ").append(String.join(", ", table.primaryKey)).append("\n");
            }
            for (var fk : table.foreignKeys) {
                ddl.append("-- Foreign key: ")
                        .append(fk.fkColumn)
                        .append(" references ")
                        .append(fk.pkTable)
                        .append("(")
                        .append(fk.pkColumn)
                        .append(")\n");
            }
            if (!table.primaryKey.isEmpty() || !table.foreignKeys.isEmpty()) {
                ddl.append("\n");
            }
        }
        return ddl.toString().trim();
    }

    public static String buildSqlCoderPrompt(SchemaService.SchemaInfo schema,
                                             String question,
                                             String errorHint) {
        if (schema == null) throw new IllegalArgumentException("Schema is not loaded");
        if (question == null || question.isBlank()) throw new IllegalArgumentException("Question is empty");

        String dialect = normalizeDialect(schema.dialect);
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an expert ")
                .append(dialect)
                .append(" SQL generator.\n");
        prompt.append("Complete the SQL query for the question using only the schema below.\n");
        prompt.append("Rules:\n");
        prompt.append("- Return SQL only.\n");
        prompt.append("- Use table aliases to prevent ambiguity.\n");
        prompt.append("- Use only tables and columns that exist in the schema.\n");
        prompt.append("- If the question asks for a derived business metric, compute it from real columns instead of inventing a column name.\n");
        prompt.append("- When calculating a percentage, return a numeric percentage value using 100.0 to avoid integer division.\n");

        String schemaHints = buildSchemaSpecificHints(schema);
        if (!schemaHints.isBlank()) {
            prompt.append("Hints:\n").append(schemaHints);
        }
        if (errorHint != null && !errorHint.isBlank()) {
            prompt.append("Previous attempt issue: ").append(errorHint).append(".\n");
        }

        prompt.append("\nSchema:\n");
        prompt.append(buildSchemaDdl(schema)).append("\n\n");
        prompt.append("Question: ").append(question).append("\n");
        prompt.append("SQL:\nSELECT ");
        return prompt.toString();
    }

    public static String normalizeSqlReply(String rawReply) {
        String candidate = rawReply == null ? "" : rawReply.trim();
        if (candidate.isBlank()) {
            return "";
        }

        candidate = stripWrapping(candidate);

        Matcher sqlStart = Pattern.compile("(?is)\\b(with|select)\\b.*").matcher(candidate);
        if (sqlStart.find()) {
            candidate = sqlStart.group().trim();
        } else if (looksLikeSelectContinuation(candidate)) {
            candidate = "SELECT " + candidate.trim();
        }

        int semicolon = candidate.indexOf(';');
        if (semicolon >= 0) {
            candidate = candidate.substring(0, semicolon).trim();
        }

        return candidate;
    }

    public static ValidationResult validateSqlAgainstSchema(String sql, SchemaService.SchemaInfo schema) {
        if (sql == null || sql.isBlank()) {
            return ValidationResult.invalid(List.of("SQL is empty"));
        }
        if (schema == null) {
            return ValidationResult.invalid(List.of("Schema is not loaded"));
        }

        String normalizedSql = stripStringLiterals(sql)
                .replace('`', ' ')
                .replace('"', ' ');
        Set<String> tables = new LinkedHashSet<>();
        Set<String> columns = new LinkedHashSet<>();
        for (var table : schema.tables) {
            tables.add(table.name.toLowerCase(Locale.ROOT));
            for (var column : table.columns) {
                columns.add(column.name.toLowerCase(Locale.ROOT));
            }
        }

        CteMetadata cte = extractCteMetadata(normalizedSql);
        Set<String> knownSources = new LinkedHashSet<>(tables);
        knownSources.addAll(cte.names);

        Set<String> aliases = extractAliases(normalizedSql, knownSources);
        aliases.addAll(extractSelectAliases(normalizedSql));
        aliases.addAll(cte.names);
        aliases.addAll(cte.columns);
        Set<String> functionNames = extractFunctionNames(normalizedSql);
        List<String> tokens = tokenize(normalizedSql);
        LinkedHashSet<String> unknown = new LinkedHashSet<>();

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i).toLowerCase(Locale.ROOT);
            if (SQL_KEYWORDS.contains(token) || SQL_FUNCTIONS.contains(token) || functionNames.contains(token) || aliases.contains(token)) continue;

            if (i > 0) {
                String previous = tokens.get(i - 1).toLowerCase(Locale.ROOT);
                if (previous.equals("from") || previous.equals("join")) {
                    if (!knownSources.contains(token) && !aliases.contains(token)) {
                        unknown.add(tokens.get(i));
                    }
                    continue;
                }
            }

            if (tables.contains(token) || columns.contains(token)) continue;
            if (token.matches("\\d+")) continue;
            unknown.add(tokens.get(i));
        }

        if (unknown.isEmpty()) {
            return ValidationResult.valid();
        }
        return ValidationResult.invalid(new ArrayList<>(unknown));
    }

    private static Set<String> extractAliases(String sql, Set<String> knownSources) {
        Set<String> aliases = new LinkedHashSet<>();
        Pattern fromJoin = Pattern.compile("(?i)\\b(?:from|join)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+(?:as\\s+)?([A-Za-z_][A-Za-z0-9_]*)");
        Matcher matcher = fromJoin.matcher(sql);
        while (matcher.find()) {
            String source = matcher.group(1).toLowerCase(Locale.ROOT);
            String alias = matcher.group(2).toLowerCase(Locale.ROOT);
            if (knownSources.contains(source) && !SQL_KEYWORDS.contains(alias)) {
                aliases.add(alias);
            }
        }
        return aliases;
    }

    private static Set<String> extractFunctionNames(String sql) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?i)\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(").matcher(sql);
        while (matcher.find()) {
            names.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static CteMetadata extractCteMetadata(String sql) {
        Set<String> names = new LinkedHashSet<>();
        Set<String> columns = new LinkedHashSet<>();

        Matcher matcher = Pattern.compile("(?i)(?:\\bwith\\b|,)\\s*([A-Za-z_][A-Za-z0-9_]*)(?:\\s*\\(([^)]*)\\))?\\s+as\\s*\\(").matcher(sql);
        while (matcher.find()) {
            String cteName = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!SQL_KEYWORDS.contains(cteName)) {
                names.add(cteName);
            }

            String cteColumns = matcher.group(2);
            if (cteColumns != null && !cteColumns.isBlank()) {
                for (String raw : cteColumns.split(",")) {
                    String value = raw.trim().toLowerCase(Locale.ROOT);
                    if (!value.isBlank() && !SQL_KEYWORDS.contains(value)) {
                        columns.add(value);
                    }
                }
            }
        }
        return new CteMetadata(names, columns);
    }

    private static Set<String> extractSelectAliases(String sql) {
        Set<String> aliases = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?i)\\bas\\s+([A-Za-z_][A-Za-z0-9_]*)").matcher(sql);
        while (matcher.find()) {
            String alias = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!SQL_KEYWORDS.contains(alias)) {
                aliases.add(alias);
            }
        }
        return aliases;
    }

    private static List<String> tokenize(String sql) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|\\d+").matcher(sql);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static String buildSchemaSpecificHints(SchemaService.SchemaInfo schema) {
        Set<String> tables = new LinkedHashSet<>();
        Set<String> columns = new LinkedHashSet<>();
        for (var table : schema.tables) {
            tables.add(table.name.toLowerCase(Locale.ROOT));
            for (var column : table.columns) {
                columns.add(column.name.toLowerCase(Locale.ROOT));
            }
        }

        StringBuilder hints = new StringBuilder();
        if (tables.contains("tracks") && (tables.contains("invoice_items") || tables.contains("invoice_lines"))) {
            hints.append("- For best-selling tracks, join tracks with invoice_items or invoice_lines and rank using SUM(quantity) or SUM(unit_price * quantity). Do not invent a Sales column.\n");
        }
        if (tables.contains("customers") && tables.contains("invoices") && columns.contains("total")) {
            hints.append("- For customer revenue questions, join customers to invoices on CustomerId and aggregate invoices.Total by customer.\n");
            hints.append("- For customer share of total sales, divide each customer's SUM(invoices.Total) * 100.0 by (SELECT SUM(Total) FROM invoices).\n");
        } else if (tables.contains("customers") && (tables.contains("invoices") || tables.contains("orders"))) {
            hints.append("- For customer purchase questions, derive totals from invoice or order tables instead of inventing aggregated columns.\n");
        }
        if (columns.contains("unit_price") && columns.contains("quantity")) {
            hints.append("- Revenue can be derived as unit_price * quantity when the question asks about sales amount or earnings.\n");
        }
        return hints.toString();
    }

    private static boolean looksLikeSelectContinuation(String candidate) {
        String value = candidate.toLowerCase(Locale.ROOT).trim();
        if (!value.contains(" from ")) {
            return false;
        }
        return !value.startsWith("please")
                && !value.startsWith("the ")
                && !value.startsWith("sql")
                && !value.startsWith("query")
                && !value.startsWith("--");
    }

    private static String stripWrapping(String candidate) {
        String value = candidate;
        if (value.startsWith("```")) {
            int firstNl = value.indexOf('\n');
            if (firstNl > 0) {
                value = value.substring(firstNl + 1);
            }
            if (value.endsWith("```")) {
                value = value.substring(0, value.length() - 3);
            }
        }
        return value.trim();
    }

    private static String stripStringLiterals(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        return sql.replaceAll("'([^']|'')*'", " ");
    }

    private static String normalizeDialect(String dialect) {
        if (dialect == null || dialect.isBlank()) return "SQL";
        return switch (dialect.trim().toLowerCase(Locale.ROOT)) {
            case "postgres", "postgresql" -> "Postgres";
            case "sqlite" -> "SQLite";
            case "mysql" -> "MySQL";
            default -> dialect;
        };
    }

    private static String mapType(String src) {
        if (src == null) return "TEXT";
        String value = src.toLowerCase(Locale.ROOT);
        if (value.contains("int")) return "INTEGER";
        if (value.contains("char") || value.contains("text") || value.contains("clob") || value.contains("string") || value.contains("varchar")) return "TEXT";
        if (value.contains("date") || value.contains("time")) return "TIMESTAMP";
        if (value.contains("real") || value.contains("double") || value.contains("float") || value.contains("decimal") || value.contains("numeric")) return "DOUBLE";
        if (value.contains("bool")) return "BOOLEAN";
        if (value.contains("blob")) return "BLOB";
        return "TEXT";
    }

    public static final class ValidationResult {
        private final boolean valid;
        private final List<String> unknownIdentifiers;

        private ValidationResult(boolean valid, List<String> unknownIdentifiers) {
            this.valid = valid;
            this.unknownIdentifiers = unknownIdentifiers;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult invalid(List<String> unknownIdentifiers) {
            return new ValidationResult(false, List.copyOf(unknownIdentifiers));
        }

        public boolean isValid() {
            return valid;
        }

        public List<String> getUnknownIdentifiers() {
            return unknownIdentifiers;
        }

        public String formatMessage() {
            if (valid) return "SQL matches the loaded schema.";
            if (unknownIdentifiers.size() == 1) {
                String single = unknownIdentifiers.get(0);
                if (single.contains(" ")) {
                    return single;
                }
            }
            return "Unknown identifiers: " + String.join(", ", unknownIdentifiers);
        }
    }

    private record CteMetadata(Set<String> names, Set<String> columns) {
    }
}
