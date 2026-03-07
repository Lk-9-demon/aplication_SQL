package com.dyploma.app;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class OpenAiQuickTest {
    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("API_KEY_OPENAI");


        String question = "Explain in 1 sentence what JDBC is.";

        // Request body for Responses API: model + input
        JsonObject body = new JsonObject();
        body.addProperty("model", "gpt-4.1-mini");   // можна змінити пізніше
        body.addProperty("input", question);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/responses"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            System.out.println("HTTP " + resp.statusCode());
            System.out.println(resp.body());
            return;
        }

        // Parse output_text from response
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();

        // Many responses include "output" array with text parts.
        // We'll extract any "output_text" if present, else print full JSON.
        if (json.has("output_text")) {
            System.out.println(json.get("output_text").getAsString());
        } else {
            System.out.println(resp.body());
        }
    }
}
