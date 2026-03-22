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

        String system = "You are an assistant that generates strictly valid SQL for the specified RDBMS. " +
                "Return only SQL without explanations. Use LIMIT 1000 where applicable. Dialect: " + schema.dialect + ".";

        String user = "Schema (brief): " + schemaBrief + "\nQuestion: " + question +
                "\nGenerate a single SQL statement that answers the question.";

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
