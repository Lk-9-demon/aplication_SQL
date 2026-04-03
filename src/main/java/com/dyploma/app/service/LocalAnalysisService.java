package com.dyploma.app.service;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LocalAnalysisService {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Gson gson = new Gson();

    public String getConfiguredUrl() {
        return getConfiguredAnalysisUrl();
    }

    public String getConfiguredAnalysisUrl() {
        return firstNonBlank(
                System.getenv("LOCAL_AI_ANALYSIS_URL"),
                System.getenv("LOCAL_AI_URL"),
                "http://localhost:11434/api/chat"
        );
    }

    public String getConfiguredSqlUrl() {
        return firstNonBlank(
                System.getenv("LOCAL_AI_SQL_URL"),
                toGenerateUrl(System.getenv("LOCAL_AI_URL")),
                "http://localhost:11434/api/generate"
        );
    }

    public String getConfiguredSqlModel() {
        return firstNonBlank(
                System.getenv("LOCAL_AI_SQL_MODEL"),
                System.getenv("LOCAL_AI_MODEL"),
                "sqlcoder"
        );
    }

    public String getConfiguredAnalysisModel() {
        return firstNonBlank(
                System.getenv("LOCAL_AI_ANALYSIS_MODEL"),
                System.getenv("LOCAL_ANALYST_MODEL"),
                "qwen3:8b"
        );
    }

    public String chat(String system, String user) throws Exception {
        return chatWithAnalysisModel(system, user);
    }

    public String chatWithSqlModel(String system, String user) throws Exception {
        return generateWithModel(getConfiguredSqlUrl(), getConfiguredSqlModel(), system, user);
    }

    public String chatWithAnalysisModel(String system, String user) throws Exception {
        return chatWithModel(getConfiguredAnalysisUrl(), getConfiguredAnalysisModel(), system, user);
    }

    private String chatWithModel(String url, String model, String system, String user) throws Exception {
        ChatRequest req = new ChatRequest();
        req.model = model;
        req.messages = List.of(
                new Msg("system", system == null ? "You are a local data analysis assistant." : system),
                new Msg("user", user == null ? "Hello!" : user)
        );
        boolean wantStream = wantStream();
        req.stream = wantStream ? null : Boolean.FALSE;

        String body = gson.toJson(req);
        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        if (!wantStream) {
            HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("Local AI error: HTTP " + resp.statusCode() + " - " + resp.body());
            }
            try {
                ChatResponseOllama o = gson.fromJson(resp.body(), ChatResponseOllama.class);
                if (o != null && o.message != null && o.message.content != null) {
                    String out = stripCodeFence(o.message.content).trim();
                    if (out.endsWith(";")) out = out.substring(0, out.length() - 1).trim();
                    return out;
                }
            } catch (Exception ignore) {
            }
            try {
                ChatResponseOpenAI c = gson.fromJson(resp.body(), ChatResponseOpenAI.class);
                if (c != null && c.choices != null && !c.choices.isEmpty() && c.choices.get(0).message != null) {
                    String content = c.choices.get(0).message.content;
                    String out = stripCodeFence(content).trim();
                    if (out.endsWith(";")) out = out.substring(0, out.length() - 1).trim();
                    return out;
                }
            } catch (Exception ignore) {
            }
            String raw = stripCodeFence(resp.body()).trim();
            if (raw.endsWith(";")) raw = raw.substring(0, raw.length() - 1).trim();
            return raw;
        }

        HttpResponse<java.io.InputStream> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new RuntimeException("Local AI error: HTTP " + resp.statusCode() + " - " + err);
        }

        try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            boolean any = false;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                any = true;
                try {
                    StreamChunk chunk = gson.fromJson(line, StreamChunk.class);
                    if (chunk != null) {
                        if (chunk.message != null && chunk.message.content != null) {
                            sb.append(chunk.message.content);
                        }
                        if (Boolean.TRUE.equals(chunk.done)) {
                            break;
                        }
                        continue;
                    }
                } catch (Exception ignore) {
                }

                try {
                    ChatResponseOpenAI c = gson.fromJson(line, ChatResponseOpenAI.class);
                    if (c != null && c.choices != null && !c.choices.isEmpty() && c.choices.get(0).message != null) {
                        String content = c.choices.get(0).message.content;
                        return content == null ? "" : content.trim();
                    }
                } catch (Exception ignore) {
                }
            }
            String out = sb.toString();
            if (!any && out.isBlank()) {
                return out;
            }
            out = stripCodeFence(out).trim();
            if (out.endsWith(";")) out = out.substring(0, out.length() - 1).trim();
            return out;
        }
    }

    private String generateWithModel(String url, String model, String system, String user) throws Exception {
        GenerateRequest req = new GenerateRequest();
        req.model = model;
        req.prompt = buildGeneratePrompt(system, user);
        req.stream = Boolean.FALSE;

        String body = gson.toJson(req);
        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Local AI error: HTTP " + resp.statusCode() + " - " + resp.body());
        }

        try {
            GenerateResponseOllama o = gson.fromJson(resp.body(), GenerateResponseOllama.class);
            if (o != null && o.response != null) {
                String out = stripCodeFence(o.response).trim();
                if (out.endsWith(";")) out = out.substring(0, out.length() - 1).trim();
                return out;
            }
        } catch (Exception ignore) {
        }

        String raw = stripCodeFence(resp.body()).trim();
        if (raw.endsWith(";")) raw = raw.substring(0, raw.length() - 1).trim();
        return raw;
    }

    public String explainQueryResult(String question,
                                     SchemaService.SchemaInfo schema,
                                     String sql,
                                     List<String> columns,
                                     List<List<Object>> sampleRows,
                                     long totalRows) throws Exception {
        if (schema == null) throw new IllegalArgumentException("Schema is not loaded");
        if (question == null || question.isBlank()) throw new IllegalArgumentException("Question is empty");

        List<String> cols = columns == null ? List.of() : columns;
        List<List<String>> printableRows = new java.util.ArrayList<>();
        if (sampleRows != null) {
            int limit = Math.min(sampleRows.size(), 25);
            for (int i = 0; i < limit; i++) {
                List<Object> row = sampleRows.get(i);
                List<String> printable = new java.util.ArrayList<>();
                if (row != null) {
                    for (Object value : row) {
                        printable.add(value == null ? "null" : String.valueOf(value));
                    }
                }
                printableRows.add(printable);
            }
        }

        String system = "You are a local database analysis assistant. " +
                "Answer using only the provided database schema, SQL, row count, columns, and sample rows. " +
                "Treat result_row_count as the number of rows returned by the query result, not as the number of invoices, customers, or business events unless an explicit count metric says so. " +
                "If you mention a value from sample rows, make it clear it comes from shown rows. " +
                "Use the database context to explain what the result means in business terms. " +
                "Do not invent columns, tables, joins, or statistics. Keep the answer concise and practical.";

        StringBuilder user = new StringBuilder();
        user.append("Database overview: ").append(buildSchemaBrief(schema)).append("\n");
        user.append("Full schema context:\n").append(buildSchemaContext(schema)).append("\n");
        String relevantSchemaContext = buildRelevantSchemaContext(schema, sql);
        if (!relevantSchemaContext.isBlank()) {
        user.append("Relevant tables for this query:\n").append(relevantSchemaContext).append("\n");
        }
        user.append("Dialect: ").append(schema.dialect).append("\n");
        user.append("Question: ").append(question).append("\n");
        user.append("SQL used: ").append(sql == null ? "" : sql).append("\n");
        user.append("result_row_count: ").append(totalRows >= 0 ? totalRows : "unknown").append("\n");
        user.append("named_metrics: ").append(buildNamedMetrics(cols, sampleRows)).append("\n");
        user.append("columns: ").append(gson.toJson(cols)).append("\n");
        user.append("sample_rows: ").append(gson.toJson(printableRows)).append("\n");
        user.append("Provide a short answer plus 1-3 useful insights if the result supports them. Never treat result_row_count as proof of the number of invoices, orders, or customers unless named_metrics explicitly contains such a count.");

        return chatWithAnalysisModel(system, user.toString());
    }

    public String explainPlannedAnalysis(String question,
                                         SchemaService.SchemaInfo schema,
                                         AiService.AnalysisPlan plan,
                                         List<AnalysisEvidence> evidences) throws Exception {
        if (schema == null) throw new IllegalArgumentException("Schema is not loaded");
        if (question == null || question.isBlank()) throw new IllegalArgumentException("Question is empty");

        List<AnalysisEvidence> safeEvidences = evidences == null ? List.of() : evidences;

        String system = "You are a private local business analyst. " +
                "Answer using only the provided database schema, the analysis plan, and the locally executed query evidence. " +
                "Base your answer on actual numbers from the provided evidence. " +
                "If the question asks for a comparison, highlight the comparison with exact figures when available. " +
                "If the question asks for a forecast, provide a cautious directional forecast grounded in the observed trend data and clearly state assumptions. " +
                "Treat result_row_count as the number of rows returned by a supporting query, not as the number of invoices, orders, or customers unless named_metrics explicitly contains those counts. " +
                "If the user asks about profit but the evidence only supports revenue or sales, say that true profit is unavailable and continue with the supported revenue figures. " +
                "If evidence includes aliases such as revenue_total, invoice_count, customer_count, avg_invoice_value, period_start, period_end, year, month, or contributor labels, use them directly. " +
                "Prefer a structured answer with sections like Short answer, Key metrics, Breakdown, Top contributors, and Limitation, but include only sections supported by the evidence. " +
                "Do not invent metrics, rows, dates, or causes that are not supported by the evidence. " +
                "Do not expose SQL unless it is necessary to explain a limitation. Keep the answer concise and practical.";

        StringBuilder user = new StringBuilder();
        user.append("Database overview: ").append(buildSchemaBrief(schema)).append("\n");
        user.append("Relevant schema context:\n").append(buildRelevantSchemaContext(schema, collectSql(safeEvidences))).append("\n");
        if (plan != null) {
            user.append("Analysis intent: ").append(blankToUnknown(plan.intent)).append("\n");
            user.append("Analysis goal: ").append(blankToUnknown(plan.analysisGoal)).append("\n");
        }
        user.append("Question: ").append(question).append("\n");
        user.append("Executed evidence:\n");
        for (AnalysisEvidence evidence : safeEvidences) {
            user.append("- id: ").append(blankToUnknown(evidence.id)).append("\n");
            user.append("  purpose: ").append(blankToUnknown(evidence.purpose)).append("\n");
            user.append("  result_row_count: ").append(evidence.resultRowCount >= 0 ? evidence.resultRowCount : "unknown").append("\n");
            user.append("  named_metrics: ").append(buildNamedMetrics(evidence.columns, evidence.rows)).append("\n");
            user.append("  columns: ").append(gson.toJson(evidence.columns == null ? List.of() : evidence.columns)).append("\n");
            user.append("  numeric_highlights: ").append(buildNumericHighlights(evidence.columns, evidence.rows)).append("\n");
            user.append("  sample_rows: ").append(gson.toJson(toPrintableRows(evidence.rows, 20))).append("\n");
        }
        user.append("Answer with a short analysis and 1-3 concrete insights. If there is not enough evidence for a trustworthy conclusion, say exactly what is missing. Never infer business-record counts from result_row_count alone.\n");
        user.append("Formatting preference: start with a direct answer sentence, then list exact metrics, then optional breakdowns or top contributors if the evidence supports them.");

        return chatWithAnalysisModel(system, user.toString());
    }

    private String buildSchemaContext(SchemaService.SchemaInfo schema) {
        return LocalSqlSupport.buildSchemaDdl(schema);
    }

    private String buildSchemaBrief(SchemaService.SchemaInfo schema) {
        StringBuilder schemaBrief = new StringBuilder();
        schemaBrief.append("{dialect:'").append(schema.dialect).append("', tables:[");
        for (int i = 0; i < schema.tables.size(); i++) {
            var t = schema.tables.get(i);
            schemaBrief.append("{name:'").append(t.name).append("', cols:[");
            for (int j = 0; j < t.columns.size(); j++) {
                var c = t.columns.get(j);
                schemaBrief.append("'").append(c.name).append("'");
                if (j + 1 < t.columns.size()) schemaBrief.append(",");
            }
            schemaBrief.append("]}");
            if (i + 1 < schema.tables.size()) schemaBrief.append(",");
        }
        schemaBrief.append("]}");
        return schemaBrief.toString();
    }

    private String buildRelevantSchemaContext(SchemaService.SchemaInfo schema, String sql) {
        if (schema == null || sql == null || sql.isBlank()) {
            return "";
        }

        Set<String> relevantTables = extractTableNames(sql);
        if (relevantTables.isEmpty()) {
            return "";
        }

        StringBuilder details = new StringBuilder();
        for (var table : schema.tables) {
            if (!relevantTables.contains(table.name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            details.append("Table ").append(table.name).append(": columns=");
            for (int i = 0; i < table.columns.size(); i++) {
                var column = table.columns.get(i);
                details.append(column.name).append(" ").append(column.type);
                if (i + 1 < table.columns.size()) {
                    details.append(", ");
                }
            }
            details.append(". ");
            if (!table.primaryKey.isEmpty()) {
                details.append("Primary key=").append(String.join(", ", table.primaryKey)).append(". ");
            }
            if (!table.foreignKeys.isEmpty()) {
                details.append("Foreign keys=");
                for (int i = 0; i < table.foreignKeys.size(); i++) {
                    var fk = table.foreignKeys.get(i);
                    details.append(fk.fkColumn)
                            .append(" -> ")
                            .append(fk.pkTable)
                            .append("(")
                            .append(fk.pkColumn)
                            .append(")");
                    if (i + 1 < table.foreignKeys.size()) {
                        details.append(", ");
                    }
                }
                details.append(". ");
            }
            details.append("\n");
        }
        return details.toString().trim();
    }

    private List<List<String>> toPrintableRows(List<List<Object>> rows, int maxRows) {
        List<List<String>> printableRows = new java.util.ArrayList<>();
        if (rows == null) {
            return printableRows;
        }
        int limit = Math.min(rows.size(), Math.max(maxRows, 0));
        for (int i = 0; i < limit; i++) {
            List<Object> row = rows.get(i);
            List<String> printable = new java.util.ArrayList<>();
            if (row != null) {
                for (Object value : row) {
                    printable.add(value == null ? "null" : String.valueOf(value));
                }
            }
            printableRows.add(printable);
        }
        return printableRows;
    }

    private String buildNumericHighlights(List<String> columns, List<List<Object>> rows) {
        if (columns == null || columns.isEmpty() || rows == null || rows.isEmpty()) {
            return "none";
        }

        java.util.Map<String, java.util.List<Double>> valuesByColumn = new java.util.LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            valuesByColumn.put(columns.get(i), new java.util.ArrayList<>());
        }

        for (List<Object> row : rows) {
            if (row == null) continue;
            for (int i = 0; i < Math.min(columns.size(), row.size()); i++) {
                Object value = row.get(i);
                if (value instanceof Number number) {
                    valuesByColumn.get(columns.get(i)).add(number.doubleValue());
                    continue;
                }
                if (value instanceof String text) {
                    try {
                        valuesByColumn.get(columns.get(i)).add(Double.parseDouble(text));
                    } catch (Exception ignore) {
                    }
                }
            }
        }

        java.util.Map<String, java.util.Map<String, Double>> summary = new java.util.LinkedHashMap<>();
        for (var entry : valuesByColumn.entrySet()) {
            List<Double> values = entry.getValue();
            if (values.isEmpty()) continue;
            double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            java.util.Map<String, Double> stats = new java.util.LinkedHashMap<>();
            stats.put("min", min);
            stats.put("max", max);
            stats.put("avg", avg);
            summary.put(entry.getKey(), stats);
        }

        return summary.isEmpty() ? "none" : gson.toJson(summary);
    }

    private String buildNamedMetrics(List<String> columns, List<List<Object>> rows) {
        if (columns == null || columns.isEmpty() || rows == null || rows.isEmpty()) {
            return "none";
        }

        int rowLimit = Math.min(rows.size(), 3);
        List<java.util.Map<String, String>> mappedRows = new java.util.ArrayList<>();
        for (int rowIndex = 0; rowIndex < rowLimit; rowIndex++) {
            List<Object> row = rows.get(rowIndex);
            java.util.Map<String, String> mapped = new java.util.LinkedHashMap<>();
            if (row != null) {
                for (int colIndex = 0; colIndex < Math.min(columns.size(), row.size()); colIndex++) {
                    Object value = row.get(colIndex);
                    mapped.put(columns.get(colIndex), value == null ? "null" : String.valueOf(value));
                }
            }
            mappedRows.add(mapped);
        }

        if (mappedRows.size() == 1) {
            return gson.toJson(mappedRows.get(0));
        }
        return gson.toJson(mappedRows);
    }

    private String collectSql(List<AnalysisEvidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return "";
        }
        StringBuilder sql = new StringBuilder();
        for (AnalysisEvidence evidence : evidences) {
            if (evidence != null && evidence.sql != null && !evidence.sql.isBlank()) {
                if (sql.length() > 0) {
                    sql.append("\n");
                }
                sql.append(evidence.sql);
            }
        }
        return sql.toString();
    }

    private String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean wantStream() {
        return !"false".equalsIgnoreCase(System.getenv().getOrDefault("LOCAL_AI_STREAM", "true"));
    }

    private String buildGeneratePrompt(String system, String user) {
        StringBuilder prompt = new StringBuilder();
        if (system != null && !system.isBlank()) {
            prompt.append(system.trim()).append("\n\n");
        }
        if (user != null && !user.isBlank()) {
            prompt.append(user.trim());
        }
        return prompt.toString();
    }

    private String toGenerateUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        if (url.endsWith("/api/chat")) {
            return url.substring(0, url.length() - "/api/chat".length()) + "/api/generate";
        }
        return url;
    }

    private Set<String> extractTableNames(String sql) {
        Set<String> tables = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?i)\\b(?:from|join)\\s+([A-Za-z_][A-Za-z0-9_]*)").matcher(sql);
        while (matcher.find()) {
            tables.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return tables;
    }

    private static class ChatRequest {
        String model;
        List<Msg> messages;
        @SerializedName("temperature")
        Double temperature = 0.1;
        @SerializedName("stream")
        Boolean stream;
    }

    private static class GenerateRequest {
        String model;
        String prompt;
        @SerializedName("temperature")
        Double temperature = 0.1;
        @SerializedName("stream")
        Boolean stream;
    }

    private static class Msg {
        String role;
        String content;

        Msg(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private static class ChatResponseOllama {
        Msg message;
    }

    private static class StreamChunk {
        Msg message;
        Boolean done;
    }

    private static class GenerateResponseOllama {
        String response;
        Boolean done;
    }

    private static class ChatResponseOpenAI {
        List<Choice> choices;
    }

    private static class Choice {
        Msg message;
    }

    public static class AnalysisEvidence {
        public String id;
        public String purpose;
        public String sql;
        public long resultRowCount = -1L;
        public List<String> columns = List.of();
        public List<List<Object>> rows = List.of();
        public boolean truncated;
    }

    private static String stripCodeFence(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) t = t.substring(firstNl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        t = t.replaceAll("^sql\\s*", "");
        return t;
    }
}
