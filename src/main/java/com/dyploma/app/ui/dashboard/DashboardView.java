package com.dyploma.app.ui.dashboard;

import com.dyploma.app.dao.ConnectionDao;
import com.dyploma.app.model.User;
import com.dyploma.app.service.SchemaService;
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
        Button sqlBtn = new Button("Open SQL Console (later)");
        sqlBtn.setDisable(true);

        Button logoutBtn = new Button("Logout");

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

        VBox root = new VBox(12, title, welcome, connectionInfo, schemaStatus, refreshSchemaBtn, changeConnBtn, aiChatBtn, sqlBtn, logoutBtn);
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
        schemaStatus.setText("Schema: loading…");
        aiChatBtn.setDisable(true);

        new Thread(() -> {
            try {
                SchemaService ss = new SchemaService();
                ss.refresh(user.getId(), conn);
                Platform.runLater(() -> {
                    schemaStatus.setText("Schema: loaded (saved to file)");
                    aiChatBtn.setDisable(false);
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
