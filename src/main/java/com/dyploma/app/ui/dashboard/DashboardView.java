package com.dyploma.app.ui.dashboard;

import com.dyploma.app.dao.ConnectionDao;
import com.dyploma.app.model.User;
import com.dyploma.app.service.SchemaService;
import com.dyploma.app.service.LocalAnalysisService;
import com.dyploma.app.ui.AppState;
import com.dyploma.app.ui.ConnectionView;
import com.dyploma.app.ui.SceneManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

public class DashboardView {

    private final SceneManager sceneManager;

    public DashboardView(SceneManager sceneManager) {
        this.sceneManager = sceneManager;
    }

    public VBox build() {
        Label title = new Label("Dashboard");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        User user = AppState.getCurrentUser();
        Label welcome = new Label(user != null ? ("Welcome, " + user.getUsername()) : "Welcome");

        // Коротка інформація про активне підключення
        ConnectionDao.SavedConnection conn = null;
        if (user != null) {
            try {
                conn = new ConnectionDao().findAnyForUser(user.getId());
            } catch (Exception ignore) {
                // якщо раптом сталася помилка — просто не показуємо блок підключення
            }
        }

        Label connectionInfo;
        if (conn != null) {
            String txt;
            if ("SQLITE".equalsIgnoreCase(conn.dbType)) {
                txt = String.format("Active connection: %s | SQLITE file: %s",
                        conn.name,
                        (conn.dbFilePath != null ? conn.dbFilePath : "<unknown>"));
            } else {
                txt = String.format(
                        "Active connection: %s | %s %s:%d/%s (as %s)",
                        conn.name,
                        conn.dbType,
                        conn.host,
                        conn.port,
                        conn.database,
                        conn.username
                );
            }
            connectionInfo = new Label(txt);
        } else {
            connectionInfo = new Label("No saved connection yet");
            connectionInfo.setStyle("-fx-opacity: 0.8;");
        }

        // Статус схеми та керування
        Label schemaStatus = new Label("Schema: not loaded");
        Button refreshSchemaBtn = new Button("Refresh schema");

        Button changeConnBtn = new Button("Change connection");
        changeConnBtn.setOnAction(e -> sceneManager.switchTo(new ConnectionView(sceneManager).build(), "Connection"));

        Button aiChatBtn = new Button("Open AI Chat");
        aiChatBtn.setDisable(true); // увімкнемо лише коли є схема
        Button openLocalSqlBtn = new Button("Open Local AI Analyst");
        openLocalSqlBtn.setOnAction(e -> sceneManager.switchTo(new com.dyploma.app.ui.LocalSqlGenView(sceneManager).build(), "Local AI Analyst"));

        Button sqlBtn = new Button("Open SQL Console (later)");
        sqlBtn.setDisable(true);

        Button logoutBtn = new Button("Logout");

        // Тимчасова кнопка для перевірки локальної моделі (Ollama / duckdb-nsql)
        Button testLocalModelBtn = new Button("Test Ollama");
        testLocalModelBtn.setOnAction(e -> {
            testLocalModelBtn.setDisable(true);
            Label progress = new Label("Local AI: calling …");
            Alert a = new Alert(Alert.AlertType.INFORMATION);
            a.setHeaderText(null);
            a.setTitle("Local AI Test");
            a.getDialogPane().setContent(progress);
            a.show();

            new Thread(() -> {
                try {
                    LocalAnalysisService localAi = new LocalAnalysisService();
                    String system = "You are a local data analysis assistant. Answer concisely.";
                    String userMsg = "Briefly confirm that the local analysis model is ready.";
                    String reply = localAi.chatWithAnalysisModel(system, userMsg);
                    javafx.application.Platform.runLater(() -> {
                        a.setContentText("Reply:\n" + reply);
                        progress.setText("Reply:\n" + reply);
                        testLocalModelBtn.setDisable(false);
                    });
                } catch (Exception ex) {
                    javafx.application.Platform.runLater(() -> {
                        a.setAlertType(Alert.AlertType.ERROR);
                        a.setContentText("Error: " + ex.getMessage());
                        progress.setText("Error: " + ex.getMessage());
                        testLocalModelBtn.setDisable(false);
                    });
                }
            }, "local-ai-test").start();
        });

        aiChatBtn.setOnAction(e -> sceneManager.switchTo(new com.dyploma.app.ui.AiChatView(sceneManager).build(), "AI Chat"));

        logoutBtn.setOnAction(e -> {
            AppState.setCurrentUser(null);
            AppState.setCurrentSchema(null);
            // Повертаємось на екран автентифікації
            sceneManager.switchToAuth();
        });

        // Початковий стан кнопки чату залежно від наявності схеми
        if (AppState.getCurrentSchema() != null) {
            schemaStatus.setText("Schema: loaded");
            aiChatBtn.setDisable(false);
        }

        // Обробник актуалізації (у фоні, з оновленням UI)
        ConnectionDao.SavedConnection finalConn = conn;
        refreshSchemaBtn.setOnAction(e -> doRefreshSchema(user, finalConn, schemaStatus, aiChatBtn));

        // Автовитяг при вході, якщо схеми ще немає і є підключення
        if (AppState.getCurrentSchema() == null && conn != null && user != null) {
            doRefreshSchema(user, conn, schemaStatus, aiChatBtn);
        }

        VBox root = new VBox(12, title, welcome, connectionInfo, schemaStatus, refreshSchemaBtn, changeConnBtn, aiChatBtn, openLocalSqlBtn, testLocalModelBtn, sqlBtn, logoutBtn);
        root.setPadding(new Insets(16));
        root.setMaxWidth(720);

        return root;
    }

    private void doRefreshSchema(User user,
                                 ConnectionDao.SavedConnection conn,
                                 Label schemaStatus,
                                 Button aiChatBtn) {
        if (user == null || conn == null) {
            Alert a = new Alert(Alert.AlertType.WARNING, "No active connection or user");
            a.setHeaderText(null);
            a.showAndWait();
            return;
        }
        long startedAt = System.currentTimeMillis();
        schemaStatus.setText("Schema: loading…");
        aiChatBtn.setDisable(true);

        new Thread(() -> {
            try {
                SchemaService ss = new SchemaService();
                // [DEBUG_LOG] start refresh
                System.out.println("[DEBUG_LOG] Schema refresh started for user=" + user.getId() + 
                        ", connectionName=" + conn.name + ", type=" + conn.dbType);
                ss.refresh(user.getId(), conn);
                var schema = AppState.getCurrentSchema();
                long tookMs = System.currentTimeMillis() - startedAt;
                Platform.runLater(() -> {
                    if (schema != null && schema.tables != null) {
                        int tables = schema.tables.size();
                        schemaStatus.setText("Schema: loaded (" + tables + " tables, " + (tookMs) + " ms, saved to file)");
                        aiChatBtn.setDisable(tables == 0);
                    } else {
                        schemaStatus.setText("Schema: error — empty schema returned");
                        aiChatBtn.setDisable(true);
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    schemaStatus.setText("Schema: error — " + ex.getMessage());
                    aiChatBtn.setDisable(true);
                });
            }
        }, "schema-refresh").start();
    }
}
