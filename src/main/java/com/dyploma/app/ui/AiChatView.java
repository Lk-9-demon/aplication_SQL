package com.dyploma.app.ui;

import com.dyploma.app.service.AiService;
import com.dyploma.app.service.SchemaService;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

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

        TextArea question = new TextArea();
        question.setPromptText("Ask a question about your data in plain English...");
        question.setPrefRowCount(5);

        Button generateBtn = new Button("Generate SQL");
        Button backBtn = new Button("Back to Dashboard");
        generateBtn.setDisable(schema == null);

        TextArea sqlOut = new TextArea();
        sqlOut.setEditable(false);
        sqlOut.setPrefRowCount(10);

        Label status = new Label("Ready");

        generateBtn.setOnAction(e -> {
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
            status.setText("Generating SQL…");
            generateBtn.setDisable(true);
            new Thread(() -> {
                try {
                    String sql = new AiService().toSql(q, sc);
                    javafx.application.Platform.runLater(() -> {
                        sqlOut.setText(sql);
                        status.setText("Done");
                        generateBtn.setDisable(false);
                    });
                } catch (Exception ex) {
                    javafx.application.Platform.runLater(() -> {
                        status.setText("Error: " + ex.getMessage());
                        generateBtn.setDisable(false);
                    });
                }
            }, "ai-generate-sql").start();
        });

        backBtn.setOnAction(e -> sceneManager.switchTo(new com.dyploma.app.ui.dashboard.DashboardView(sceneManager).build(), "Dashboard"));

        HBox buttons = new HBox(10, generateBtn, backBtn);
        VBox root = new VBox(12, title, schemaHint, question, buttons, new Label("SQL:"), sqlOut, status);
        root.setPadding(new Insets(16));
        root.setMaxWidth(900);
        return root;
    }
}
