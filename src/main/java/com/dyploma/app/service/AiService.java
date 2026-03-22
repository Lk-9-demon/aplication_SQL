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
                "Do NOT include SQL or raw rows. Do NOT fabricate values. If metrics are insufficient, say what is missing.";

        StringBuilder user = new StringBuilder();
        user.append("Schema (brief): ").append(schemaBrief).append("\n");
        user.append("Dialect: ").append(schema.dialect).append("\n");
        user.append("User question: ").append(question).append("\n");
        if (metricsText != null && !metricsText.isBlank()) {
            user.append("Aggregated metrics (safe to use): ").append(metricsText).append("\n");
        } else {
            user.append("Aggregated metrics: none provided.\n");
        }
        user.append("Answer with precise numbers when available. Do NOT include SQL or raw data.");

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

        String system = "You are an assistant that generates strictly valid and executable SQL for the specified RDBMS. " +
                "Return only a single SQL statement without explanations. Use ONLY table and column names from the provided schema (case-insensitive). " +
                (isCountIntent
                        ? "For counting questions (how many/count/quantity), DO NOT return an aggregate COUNT(*) in the SQL. Instead, return a row-level SELECT that lists one row per entity (e.g., SELECT 1 FROM <table> or SELECT <pk> FROM <table>). The app will count rows itself. "
                        : (topN != null
                            ? ("For top-" + topN.n + " queries, return a SELECT that produces exactly " + topN.n + " rows ordered appropriately using ORDER BY, and DO NOT append any extra LIMIT beyond what is needed to ensure exactly " + topN.n + " rows (i.e., use LIMIT " + topN.n + "). ")
                            : "Prefer SELECT with LIMIT 1000 where applicable. ")) +
                "Do NOT use tables or columns not present in the schema. Dialect: " + schema.dialect + ".";

        String user = "Schema (brief): " + schemaBrief + "\nQuestion: " + question +
                (isCountIntent
                        ? "\nGenerate ONE row-level SELECT (or WITH ... SELECT) that returns one row per item being counted. Do NOT include COUNT(*). Do not include semicolons."
                        : (topN != null
                            ? ("\nGenerate ONE SELECT (or WITH ... SELECT) that returns exactly " + topN.n + " rows representing the requested top records. Use ORDER BY on the appropriate metric and include LIMIT " + topN.n + ". Do not include semicolons.")
                            : "\nGenerate a single valid SELECT (or WITH ... SELECT) that answers the question. Do not include semicolons."));

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

    private boolean isCountQuestion(String question) {
        String q = question == null ? "" : question.toLowerCase();
        return q.contains("how many") || q.contains("скільки") || q.contains("сколько") || q.contains("count ") || q.startsWith("count");
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
}
