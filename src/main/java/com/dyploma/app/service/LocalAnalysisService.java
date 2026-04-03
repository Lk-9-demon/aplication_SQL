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
                "gemma3:1b"
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
                "Answer using only the provided schema, SQL, row count, columns, and sample rows. " +
                "If total_row_count is known, treat it as exact. " +
                "If you mention a value from sample rows, make it clear it comes from shown rows. " +
                "Do not invent columns, tables, or statistics. Keep the answer concise and practical.";

        StringBuilder user = new StringBuilder();
        user.append("Schema (brief): ").append(buildSchemaBrief(schema)).append("\n");
        user.append("Dialect: ").append(schema.dialect).append("\n");
        user.append("Question: ").append(question).append("\n");
        user.append("SQL used: ").append(sql == null ? "" : sql).append("\n");
        user.append("total_row_count: ").append(totalRows >= 0 ? totalRows : "unknown").append("\n");
        user.append("columns: ").append(gson.toJson(cols)).append("\n");
        user.append("sample_rows: ").append(gson.toJson(printableRows)).append("\n");
        user.append("Provide a short answer plus 1-3 useful insights if the result supports them.");

        return chatWithAnalysisModel(system, user.toString());
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
