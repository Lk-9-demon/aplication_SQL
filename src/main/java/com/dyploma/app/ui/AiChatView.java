package com.dyploma.app.ui;

import com.dyploma.app.dao.ConnectionDao;
import com.dyploma.app.model.User;
import com.dyploma.app.service.AiService;
import com.dyploma.app.service.SchemaService;
import com.dyploma.app.service.SqlExecuteService;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

public class AiChatView {

    private final SceneManager sceneManager;

    public AiChatView(SceneManager sceneManager) {
        this.sceneManager = sceneManager;
    }

    public VBox build() {
        Label title = new Label("AI Assistant");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        SchemaService.SchemaInfo schema = AppState.getCurrentSchema();
        Label schemaHint = new Label(schema != null ? "Schema: loaded" : "Schema: not loaded (refresh on Dashboard)");

        // Історія чату
        TextArea chatHistory = new TextArea();
        chatHistory.setEditable(false);
        chatHistory.setPrefRowCount(16);

        TextArea question = new TextArea();
        question.setPromptText("Ask a question about your data in plain English...");
        question.setPrefRowCount(4);

        Button askBtn = new Button("Ask");
        Button backBtn = new Button("Back to Dashboard");
        askBtn.setDisable(schema == null);

        Label status = new Label("Ready");

        askBtn.setOnAction(e -> {
            String q = question.getText();
            if (q == null || q.isBlank()) {
                status.setText("Enter a question first");
                return;
            }
            SchemaService.SchemaInfo sc = AppState.getCurrentSchema();
            if (sc == null) {
                status.setText("Schema is not loaded — go to Dashboard and click 'Refresh schema'");
                return;
            }

            // знайдемо активне підключення для виконання SQL локально
            User user = AppState.getCurrentUser();
            if (user == null) {
                status.setText("Not logged in");
                return;
            }
            ConnectionDao.SavedConnection conn = new ConnectionDao().findAnyForUser(user.getId());
            if (conn == null) {
                status.setText("No saved connection");
                return;
            }

            appendChat(chatHistory, "user", q);
            status.setText("Thinking… (SQL generation)");
            askBtn.setDisable(true);

            new Thread(() -> {
                try {
                    AiService ai = new AiService();
                    String sql = ai.toSql(q, sc); // не показуємо користувачу

                    // Локальне виконання SQL із лімітами/без DDL
                    SqlExecuteService exec = new SqlExecuteService();
                    SqlExecuteService.QueryResult r = exec.querySample(conn, sql, 10, 10);

                    // Етап 2: отримати пояснення для користувача
                    String explanation = ai.toExplanation(q, sc, sql, r.columns != null ? r.columns : new ArrayList<>(),
                            r.rows != null ? r.rows : new ArrayList<>());

                    javafx.application.Platform.runLater(() -> {
                        appendChat(chatHistory, "assistant", explanation);
                        status.setText("Done");
                        askBtn.setDisable(false);
                        question.clear();
                    });
                } catch (Exception ex) {
                    javafx.application.Platform.runLater(() -> {
                        appendChat(chatHistory, "assistant", "Error: " + ex.getMessage());
                        status.setText("Error");
                        askBtn.setDisable(false);
                    });
                }
            }, "ai-chat").start();
        });

        backBtn.setOnAction(e -> sceneManager.switchTo(new com.dyploma.app.ui.dashboard.DashboardView(sceneManager).build(), "Dashboard"));

        HBox buttons = new HBox(10, askBtn, backBtn);
        VBox root = new VBox(12, title, schemaHint, chatHistory, question, buttons, status);
        root.setPadding(new Insets(16));
        root.setMaxWidth(900);
        return root;
    }

    private void appendChat(TextArea history, String role, String content) {
        String prefix = switch (role) {
            case "user" -> "You: ";
            case "assistant" -> "Assistant: ";
            default -> "";
        };
        String current = history.getText();
        if (current == null) current = "";
        if (!current.isBlank()) current += "\n\n";
        history.setText(current + prefix + content);
        history.positionCaret(history.getText().length());
    }
}
