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
        prompt.append("### Instructions:\n");
        prompt.append("Your task is to convert a question into a SQL query, given a ")
                .append(dialect)
                .append(" database schema.\n");
        prompt.append("Adhere to these rules:\n");
        prompt.append("- Deliberately go through the question and database schema word by word to appropriately answer the question.\n");
        prompt.append("- Use table aliases to prevent ambiguity.\n");
        prompt.append("- Use only tables and columns that exist in the schema.\n");
        prompt.append("- If the question asks for a derived business metric, compute it from real columns instead of inventing a column name.\n");
        prompt.append("- Return only one SQL query with no markdown, code fences, comments, or explanation.\n");

        String schemaHints = buildSchemaSpecificHints(schema);
        if (!schemaHints.isBlank()) {
            prompt.append(schemaHints);
        }
        if (errorHint != null && !errorHint.isBlank()) {
            prompt.append("- Fix this issue from the previous attempt: ").append(errorHint).append(".\n");
        }

        prompt.append("\n### Input:\n");
        prompt.append("Generate a SQL query that answers the question `").append(question).append("`.\n");
        prompt.append("This query will run on a database whose schema is represented in this string:\n");
        prompt.append(buildSchemaDdl(schema)).append("\n\n");
        prompt.append("### Response:\n");
        prompt.append("Based on your instructions, here is the SQL query I have generated to answer the question `")
                .append(question)
                .append("`:\n");
        return prompt.toString();
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

        Set<String> aliases = extractAliases(normalizedSql, tables);
        aliases.addAll(extractSelectAliases(normalizedSql));
        List<String> tokens = tokenize(normalizedSql);
        LinkedHashSet<String> unknown = new LinkedHashSet<>();

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i).toLowerCase(Locale.ROOT);
            if (SQL_KEYWORDS.contains(token) || aliases.contains(token)) continue;

            if (i > 0) {
                String previous = tokens.get(i - 1).toLowerCase(Locale.ROOT);
                if (previous.equals("from") || previous.equals("join")) {
                    if (!tables.contains(token)) {
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

    private static Set<String> extractAliases(String sql, Set<String> knownTables) {
        Set<String> aliases = new LinkedHashSet<>();
        Pattern fromJoin = Pattern.compile("(?i)\\b(?:from|join)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+(?:as\\s+)?([A-Za-z_][A-Za-z0-9_]*)");
        Matcher matcher = fromJoin.matcher(sql);
        while (matcher.find()) {
            String table = matcher.group(1).toLowerCase(Locale.ROOT);
            String alias = matcher.group(2).toLowerCase(Locale.ROOT);
            if (knownTables.contains(table) && !SQL_KEYWORDS.contains(alias)) {
                aliases.add(alias);
            }
        }
        return aliases;
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
        if (tables.contains("customers") && (tables.contains("invoices") || tables.contains("orders"))) {
            hints.append("- For customer purchase questions, derive totals from invoice or order line tables instead of inventing aggregated columns.\n");
        }
        if (columns.contains("unit_price") && columns.contains("quantity")) {
            hints.append("- Revenue can be derived as unit_price * quantity when the question asks about sales amount or earnings.\n");
        }
        return hints.toString();
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
            return "Unknown identifiers: " + String.join(", ", unknownIdentifiers);
        }
    }
}
