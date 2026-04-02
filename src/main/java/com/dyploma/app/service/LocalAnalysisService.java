package com.dyploma.app.service;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Простий клієнт для локальної моделі (Ollama/LM Studio подібний API).
 * За замовчуванням орієнтується на Ollama chat endpoint: http://localhost:11434/api/chat
 * Модель за замовчуванням: duckdb-nsql
 */
public class LocalAnalysisService {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Gson gson = new Gson();

    public String chat(String system, String user) throws Exception {
        String url = System.getenv().getOrDefault("LOCAL_AI_URL", "http://localhost:11434/api/chat");
        String model = System.getenv().getOrDefault("LOCAL_AI_MODEL", "duckdb-nsql");

        ChatRequest req = new ChatRequest();
        req.model = model;
        req.messages = List.of(
                new Msg("system", system == null ? "You are a local data analysis assistant." : system),
                new Msg("user", user == null ? "Hello!" : user)
        );
        // Керування стрімінгом через ENV: LOCAL_AI_STREAM=false вимикає стрім і просить повну відповідь
        boolean wantStream = !"false".equalsIgnoreCase(System.getenv().getOrDefault("LOCAL_AI_STREAM", "true"));
        req.stream = wantStream ? null : Boolean.FALSE; // Ollama розуміє stream:false в тілі

        String body = gson.toJson(req);
        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        if (!wantStream) {
            // Отримати повну відповідь одразу
            HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("Local AI error: HTTP " + resp.statusCode() + " - " + resp.body());
            }
            // Ollama non-stream відповідає об'єктом { message: { role, content }, done: true }
            try {
                ChatResponseOllama o = gson.fromJson(resp.body(), ChatResponseOllama.class);
                if (o != null && o.message != null && o.message.content != null) {
                    String out = stripCodeFence(o.message.content).trim();
                    if (out.endsWith(";")) out = out.substring(0, out.length()-1).trim();
                    return out;
                }
            } catch (Exception ignore) {}
            try {
                ChatResponseOpenAI c = gson.fromJson(resp.body(), ChatResponseOpenAI.class);
                if (c != null && c.choices != null && !c.choices.isEmpty() && c.choices.get(0).message != null) {
                    String content = c.choices.get(0).message.content;
                    String out = stripCodeFence(content).trim();
                    if (out.endsWith(";")) out = out.substring(0, out.length()-1).trim();
                    return out;
                }
            } catch (Exception ignore) {}
            String raw = stripCodeFence(resp.body()).trim();
            if (raw.endsWith(";")) raw = raw.substring(0, raw.length()-1).trim();
            return raw;
        } else {
            // Підтримка стрімінгу Ollama: відповідь приходить чанками JSON (one per line), кожен із message.content і done флагом
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
                        // Спроба парсингу як Ollama stream chunk
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
                    } catch (Exception ignore) {}

                    // Спроба парсингу як повна OpenAI‑сумісна відповідь (не стрімінг)
                    try {
                        ChatResponseOpenAI c = gson.fromJson(line, ChatResponseOpenAI.class);
                        if (c != null && c.choices != null && !c.choices.isEmpty() && c.choices.get(0).message != null) {
                            String content = c.choices.get(0).message.content;
                            return content == null ? "" : content.trim();
                        }
                    } catch (Exception ignore) {}
                }
                String out = sb.toString();
                if (!any && out.isBlank()) {
                    return out;
                }
                // Пост‑обробка: прибрати можливу «```sql ... ```» обгортку і крапку з комою в кінці
                out = stripCodeFence(out).trim();
                if (out.endsWith(";")) out = out.substring(0, out.length()-1).trim();
                return out;
            }
        }
    }

    // ---------- DTOs ----------
    private static class ChatRequest {
        String model;
        List<Msg> messages;
        @SerializedName("temperature")
        Double temperature = 0.1;
        // Специфічно для Ollama: можна передати stream=false, щоб отримати повну відповідь
        @SerializedName("stream")
        Boolean stream;
    }

    private static class Msg {
        String role;
        String content;
        Msg(String role, String content) { this.role = role; this.content = content; }
    }

    // Ollama chat-like response
    private static class ChatResponseOllama {
        Msg message;
    }

    // Ollama streaming chunk
    private static class StreamChunk {
        Msg message;
        Boolean done;
    }

    // OpenAI-compatible response
    private static class ChatResponseOpenAI {
        List<Choice> choices;
    }

    private static class Choice {
        Msg message;
    }

    private static String stripCodeFence(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            // видаляємо перший рядок ```... і останній ``` якщо є
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) t = t.substring(firstNl+1);
            if (t.endsWith("```")) t = t.substring(0, t.length()-3);
        }
        // інколи модель додає мітку мови "sql" після ```
        t = t.replaceAll("^sql\\s*", "");
        return t;
    }
}
