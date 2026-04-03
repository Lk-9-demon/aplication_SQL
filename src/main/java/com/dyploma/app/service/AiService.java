package com.dyploma.app.service;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Простий сервіс для перетворення питання користувача у SQL з урахуванням схеми.
 * Використовує OpenAI Chat Completions API через HTTP. Не зберігає дані.
 */
public class AiService {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Gson gson = new Gson();

    public boolean isPlannerAvailable() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Варіант пояснення з передачею лише агрегованих метрик (без сирих рядків).
     * metricsText може містити короткий перелік ключ=значення (наприклад: "row_count=123; avg_price=10.5").
     */
    public String toExplanationWithMetrics(String question,
                                           SchemaService.SchemaInfo schema,
                                           String sql,
                                           String metricsText) throws Exception {
        if (question == null || question.isBlank()) throw new IllegalArgumentException("Question is empty");
        if (schema == null) throw new IllegalArgumentException("Schema is not loaded");

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return "Answer (demo, no AI key):\n" +
                    "Your question: " + question + "\n" +
                    (metricsText != null && !metricsText.isBlank() ? ("Metrics: " + metricsText + "\n") : "") +
                    "Based on schema metadata only. No raw data used.";
        }

        String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");

        // Стиснений опис схеми
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

        String system = "You are a precise data analyst. Provide a correct, concise answer based ONLY on the user's question, the provided schema metadata, and the provided AGGREGATED METRICS. " +
                "If a metric 'row_count' is provided, you MUST use it as the exact numeric result when the user's question asks for a count. " +
                "Do NOT claim metrics are missing when 'row_count' is present. Do NOT include SQL or raw rows. Do NOT fabricate values. If no relevant metrics are provided, say what is missing.";

        StringBuilder user = new StringBuilder();
        user.append("Schema (brief): ").append(schemaBrief).append("\n");
        user.append("Dialect: ").append(schema.dialect).append("\n");
        user.append("User question: ").append(question).append("\n");
        if (metricsText != null && !metricsText.isBlank()) {
            user.append("Aggregated metrics (safe to use): ").append(metricsText).append("\n");
        } else {
            user.append("Aggregated metrics: none provided.\n");
        }
        user.append("Answer with precise numbers when available. If 'row_count' is present and the question asks 'how many', answer like: 'There are <row_count> ...'. Do NOT include SQL or raw data.");

        ChatRequest req = new ChatRequest();
        req.model = model;
        req.messages = List.of(
                new ChatMessage("system", system),
                new ChatMessage("user", user.toString())
        );
        req.temperature = 0.1;

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(req), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("OpenAI error: HTTP " + resp.statusCode() + " - " + resp.body());
        }

        ChatResponse cr = gson.fromJson(resp.body(), ChatResponse.class);
        if (cr == null || cr.choices == null || cr.choices.isEmpty()) {
            throw new RuntimeException("Empty AI response");
        }
        String content = cr.choices.get(0).message.content;
        if (content == null) content = "";
        return content.trim();
    }

    public String toSql(String question, SchemaService.SchemaInfo schema) throws Exception {
        if (schema == null) throw new IllegalArgumentException("Schema is not loaded");
        if (question == null || question.isBlank()) throw new IllegalArgumentException("Question is empty");

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            // Якщо ключа немає — повернемо підказку замість виклику API
            return "-- OPENAI_API_KEY is not set. Example SQL (placeholder)\nSELECT 1 AS demo;";
        }

        String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");

        // Стиснений опис схеми (без надлишків), щоб не перевищувати токени
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

        boolean isCountIntent = isCountQuestion(question);
        TopNIntent topN = parseTopN(question);
        boolean isNegativeExistence = isNegativeExistenceQuestion(question);

        String system = "You are an assistant that generates strictly valid and executable SQL for the specified RDBMS. " +
                "Return only a single SQL statement without explanations. Use ONLY table and column names from the provided schema (case-insensitive). " +
                (isCountIntent
                        ? "For counting questions (how many/count/quantity), DO NOT return an aggregate COUNT(*) in the SQL. Instead, return a row-level SELECT that lists one row per entity (e.g., SELECT 1 FROM <table> or SELECT <pk> FROM <table>). The app will count rows itself. "
                        : (isNegativeExistence
                            ? "For questions about entities WITHOUT related records (e.g., customers without purchases), use a reliable anti-join pattern: either NOT EXISTS (subquery on the related table with FK to the main entity) OR LEFT JOIN ... WHERE related.pk IS NULL. Do not rely on COUNT in WHERE. Return a row-level SELECT of the main entity. "
                            : (topN != null
                                ? ("For top-" + topN.n + " queries, return a SELECT that produces exactly " + topN.n + " rows ordered appropriately using ORDER BY, and DO NOT append any extra LIMIT beyond what is needed to ensure exactly " + topN.n + " rows (i.e., use LIMIT " + topN.n + "). ")
                                : "Prefer SELECT with LIMIT 1000 where applicable. "))) +
                "Do NOT use tables or columns not present in the schema. Dialect: " + schema.dialect + ".";

        String user = "Schema (brief): " + schemaBrief + "\nQuestion: " + question +
                (isCountIntent
                        ? "\nGenerate ONE row-level SELECT (or WITH ... SELECT) that returns one row per item being counted. Do NOT include COUNT(*). Do not include semicolons."
                        : (isNegativeExistence
                            ? "\nGenerate ONE row-level SELECT of the main entity using NOT EXISTS or LEFT JOIN ... IS NULL against the related table (based on FK/PK from the given schema) to return entities that have NO related rows. Do not include semicolons."
                            : (topN != null
                                ? ("\nGenerate ONE SELECT (or WITH ... SELECT) that returns exactly " + topN.n + " rows representing the requested top records. Use ORDER BY on the appropriate metric and include LIMIT " + topN.n + ". Do not include semicolons.")
                                : "\nGenerate a single valid SELECT (or WITH ... SELECT) that answers the question. Do not include semicolons.")));

        ChatRequest req = new ChatRequest();
        req.model = model;
        req.messages = List.of(
                new ChatMessage("system", system),
                new ChatMessage("user", user)
        );
        req.temperature = 0.1;

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(req), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("OpenAI error: HTTP " + resp.statusCode() + " - " + resp.body());
        }

        ChatResponse cr = gson.fromJson(resp.body(), ChatResponse.class);
        if (cr == null || cr.choices == null || cr.choices.isEmpty()) {
            throw new RuntimeException("Empty AI response");
        }
        String content = cr.choices.get(0).message.content;
        if (content == null) content = "";
        return content.trim();
    }

    public AnalysisPlan planPrivateAnalysis(String question,
                                            SchemaService.SchemaInfo schema,
                                            String errorHint) throws Exception {
        if (schema == null) throw new IllegalArgumentException("Schema is not loaded");
        if (question == null || question.isBlank()) throw new IllegalArgumentException("Question is empty");

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not set");
        }

        String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
        String schemaSummary = buildPlannerSchemaSummary(schema);

        String system = "You are planning a private database analysis workflow. " +
                "Return JSON only. Do not answer the business question directly. " +
                "Produce a compact analysis plan with at most 3 read-only SQL queries. " +
                "Prefer aggregate, comparison, or trend queries over raw row dumps. " +
                "Use only table and column names from the schema. " +
                "Each query must be a single SELECT or WITH ... SELECT statement. " +
                "Use clear metric aliases such as revenue_total, invoice_count, customer_count, current_period_revenue, previous_period_revenue, growth_pct. " +
                "When a primary query returns an aggregate financial metric such as revenue or sales, include at least one supporting coverage query with explicit counts of contributing business records where possible. " +
                "For business questions about revenue, sales, or profit in a country, segment, or market, prefer this evidence package: " +
                "(1) one summary query with aliases like revenue_total, invoice_count, avg_invoice_value, period_start, period_end; " +
                "(2) one time breakdown query by year or month when a date column exists; " +
                "(3) one top contributors query with a readable label and revenue_total when relevant. " +
                "Never assume that one aggregate result row means one invoice, one order, or one customer. " +
                "If schema lacks cost or expense data, do not plan a true profit calculation. Plan revenue or sales evidence instead and mention that limitation in analysisGoal. " +
                "If one query is enough, return one query. " +
                "JSON format: {\"intent\":\"...\",\"analysisGoal\":\"...\",\"queries\":[{\"id\":\"q1\",\"purpose\":\"...\",\"sql\":\"SELECT ...\"}]}";

        StringBuilder user = new StringBuilder();
        user.append("Dialect: ").append(schema.dialect).append("\n");
        user.append("Question: ").append(question).append("\n");
        user.append("Schema summary: ").append(schemaSummary).append("\n");
        user.append("Constraints: max 3 queries; no INSERT/UPDATE/DELETE/ALTER/DROP; use exact schema names; keep SQL focused on analysis.\n");
        user.append("Important: if you return an aggregate revenue or sales query, also add supporting counts such as invoice_count, customer_count, order_count, or transaction_count when the schema allows it.\n");
        user.append("Prefer readable aliases in the final columns so a local analyst can explain the results safely.\n");
        if (errorHint != null && !errorHint.isBlank()) {
            user.append("Fix this problem from the previous plan: ").append(errorHint).append("\n");
        }
        user.append("Return JSON only.");

        ChatRequest req = new ChatRequest();
        req.model = model;
        req.messages = List.of(
                new ChatMessage("system", system),
                new ChatMessage("user", user.toString())
        );
        req.temperature = 0.1;

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(req), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("OpenAI error: HTTP " + resp.statusCode() + " - " + resp.body());
        }

        ChatResponse cr = gson.fromJson(resp.body(), ChatResponse.class);
        if (cr == null || cr.choices == null || cr.choices.isEmpty()) {
            throw new RuntimeException("Empty AI response");
        }

        String content = cr.choices.get(0).message.content;
        AnalysisPlan plan = parseAnalysisPlan(content);
        if (plan.queries == null) {
            plan.queries = new ArrayList<>();
        }
        if (plan.queries.size() > 3) {
            plan.queries = new ArrayList<>(plan.queries.subList(0, 3));
        }
        plan.queries.removeIf(q -> q == null || q.sql == null || q.sql.isBlank());
        if (plan.queries.isEmpty()) {
            throw new RuntimeException("Planner returned no executable queries");
        }
        return plan;
    }

    private boolean isCountQuestion(String question) {
        String q = question == null ? "" : question.toLowerCase();
        return q.contains("how many") || q.contains("скільки") || q.contains("сколько") || q.contains("count ") || q.startsWith("count");
    }

    // Інтенція «без покупок / без пов’язаних записів»
    private boolean isNegativeExistenceQuestion(String question) {
        if (question == null) return false;
        String q = question.toLowerCase();
        return q.contains("haven't made any") || q.contains("have not made any") || q.contains("without any") ||
               q.contains("without purchases") || q.contains("without orders") || q.contains("no purchases") ||
               q.contains("no orders") || q.contains("без покуп") || q.contains("без заказ") ||
               q.contains("без придбан") || q.contains("who didn't buy") || q.contains("that didn't buy");
    }

    // Детекція "top N" у питанні: top 5, top-10, first 3, highest 20 тощо (спрощено)
    private TopNIntent parseTopN(String question) {
        if (question == null) return null;
        String q = question.toLowerCase();
        // Пошук за шаблонами: "top 5", "top-5", "top5"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("top[- ]?(\\d+)").matcher(q);
        if (m.find()) {
            try { return new TopNIntent(Integer.parseInt(m.group(1))); } catch (Exception ignore) {}
        }
        // "first 5"
        m = java.util.regex.Pattern.compile("first[ ]+(\\d+)").matcher(q);
        if (m.find()) {
            try { return new TopNIntent(Integer.parseInt(m.group(1))); } catch (Exception ignore) {}
        }
        // "найкращі 5" (укр), "лучшие 5" (ru)
        m = java.util.regex.Pattern.compile("найкращ[іi][ ]+(\\d+)|лучшие[ ]+(\\d+)").matcher(q);
        if (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                String g = m.group(i);
                if (g != null) {
                    try { return new TopNIntent(Integer.parseInt(g)); } catch (Exception ignore) {}
                }
            }
        }
        return null;
    }

    private static class TopNIntent { final int n; TopNIntent(int n){ this.n = n; } }

    /**
     * Допоміжний промпт для повторної генерації SQL, коли були проблеми з іменами таблиць/колонок.
     */
    public String toSqlRetryNamesOnly(String question, SchemaService.SchemaInfo schema, String errorHint) throws Exception {
        if (schema == null) throw new IllegalArgumentException("Schema is not loaded");
        if (question == null || question.isBlank()) throw new IllegalArgumentException("Question is empty");

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return "-- OPENAI_API_KEY is not set. Example SQL (retry placeholder)\nSELECT 1 AS demo";
        }
        String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");

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

        String system = "Generate ONE valid SELECT (or WITH ... SELECT) for the specified dialect using ONLY names from schema. " +
                "Fix naming to match schema exactly (case-insensitive). Do not add tables/columns not present. Do not include semicolons.";
        String user = "Schema: " + schemaBrief + "\nQuestion: " + question +
                (errorHint != null && !errorHint.isBlank() ? ("\nPrevious error: " + errorHint) : "");

        ChatRequest req = new ChatRequest();
        req.model = model;
        req.messages = List.of(
                new ChatMessage("system", system),
                new ChatMessage("user", user)
        );
        req.temperature = 0.1;

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(req), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("OpenAI error: HTTP " + resp.statusCode() + " - " + resp.body());
        }
        ChatResponse cr = gson.fromJson(resp.body(), ChatResponse.class);
        if (cr == null || cr.choices == null || cr.choices.isEmpty()) {
            throw new RuntimeException("Empty AI response");
        }
        String content = cr.choices.get(0).message.content;
        if (content == null) content = "";
        return content.trim();
    }

    /**
     * Етап 2 (оновлено під політику безпеки):
     * Формуємо людинозрозуміле пояснення лише на основі ПИТАННЯ користувача та МЕТАДАНИХ СХЕМИ.
     * ЖОДНИХ сирих даних/рядків і навіть сам SQL у зовнішній AI сервіс не передаємо.
     * Якщо відсутній OPENAI_API_KEY — повертаємо локальне коротке пояснення як плейсхолдер.
     */
    public String toExplanation(String question,
                                SchemaService.SchemaInfo schema,
                                String sql,
                                List<String> columns,
                                List<List<Object>> sampleRows) throws Exception {
        if (question == null || question.isBlank()) throw new IllegalArgumentException("Question is empty");
        if (schema == null) throw new IllegalArgumentException("Schema is not loaded");

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            // Локальний плейсхолдер без виклику API
            StringBuilder sb = new StringBuilder();
            sb.append("Answer (demo, no AI key):\n");
            sb.append("Your question: ").append(question).append("\n");
            sb.append("Based on the available schema metadata, provide a concise, human-friendly summary.\n");
            sb.append("(No raw data was used or sent.)\n");
            return sb.toString();
        }

        String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");

        // Compact schema brief
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

        String system = "You are a careful data analyst. Provide a concise, human-friendly answer based ONLY on the user's question and the provided database schema metadata. " +
                "Do NOT reveal raw rows, do NOT include SQL, and do NOT fabricate non-existent columns. " +
                "Avoid PII and keep the answer short (bullet points or a short paragraph).";

        StringBuilder user = new StringBuilder();
        user.append("Schema (brief): ").append(schemaBrief).append("\n");
        user.append("Dialect: ").append(schema.dialect).append("\n");
        user.append("User question: ").append(question).append("\n");
        user.append("Important: Do not include SQL or raw data in the answer. Base the explanation solely on schema understanding and the question.\n");

        ChatRequest req = new ChatRequest();
        req.model = model;
        req.messages = List.of(
                new ChatMessage("system", system),
                new ChatMessage("user", user.toString())
        );
        req.temperature = 0.2;

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(req), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("OpenAI error: HTTP " + resp.statusCode() + " - " + resp.body());
        }

        ChatResponse cr = gson.fromJson(resp.body(), ChatResponse.class);
        if (cr == null || cr.choices == null || cr.choices.isEmpty()) {
            throw new RuntimeException("Empty AI response");
        }
        String content = cr.choices.get(0).message.content;
        if (content == null) content = "";
        return content.trim();
    }

    private AnalysisPlan parseAnalysisPlan(String rawContent) {
        String content = stripCodeFence(rawContent);
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            content = content.substring(start, end + 1);
        }
        AnalysisPlan plan = gson.fromJson(content, AnalysisPlan.class);
        if (plan == null) {
            throw new RuntimeException("Planner returned invalid JSON");
        }
        return plan;
    }

    private String buildPlannerSchemaSummary(SchemaService.SchemaInfo schema) {
        StringBuilder summary = new StringBuilder();
        summary.append("{dialect:'").append(schema.dialect).append("', tables:[");
        for (int i = 0; i < schema.tables.size(); i++) {
            var table = schema.tables.get(i);
            summary.append("{name:'").append(table.name).append("', cols:[");
            for (int j = 0; j < table.columns.size(); j++) {
                var column = table.columns.get(j);
                summary.append("'").append(column.name).append("'");
                if (j + 1 < table.columns.size()) summary.append(",");
            }
            summary.append("]");
            if (!table.primaryKey.isEmpty()) {
                summary.append(", pk:[").append(String.join(",", table.primaryKey)).append("]");
            }
            if (!table.foreignKeys.isEmpty()) {
                summary.append(", fk:[");
                for (int j = 0; j < table.foreignKeys.size(); j++) {
                    var fk = table.foreignKeys.get(j);
                    summary.append("'")
                            .append(fk.fkColumn)
                            .append("->")
                            .append(fk.pkTable)
                            .append(".")
                            .append(fk.pkColumn)
                            .append("'");
                    if (j + 1 < table.foreignKeys.size()) summary.append(",");
                }
                summary.append("]");
            }
            summary.append("}");
            if (i + 1 < schema.tables.size()) summary.append(",");
        }
        summary.append("]}");
        return summary.toString();
    }

    private String stripCodeFence(String text) {
        if (text == null) return "";
        String value = text.trim();
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

    // ---- DTO для OpenAI ----
    static class ChatRequest {
        String model;
        List<ChatMessage> messages = new ArrayList<>();
        Double temperature;
    }

    static class ChatMessage {
        String role;
        String content;

        ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    static class ChatResponse {
        List<Choice> choices;
    }

    static class Choice {
        ChatMessage message;
        @SerializedName("finish_reason") String finishReason;
    }

    public static class AnalysisPlan {
        public String intent;
        public String analysisGoal;
        public List<AnalysisQuery> queries = new ArrayList<>();
    }

    public static class AnalysisQuery {
        public String id;
        public String purpose;
        public String sql;
    }
}
