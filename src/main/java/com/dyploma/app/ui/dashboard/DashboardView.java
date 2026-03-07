package com.dyploma.app.ui.dashboard;

import com.dyploma.app.dao.ConnectionDao;
import com.dyploma.app.model.User;
import com.dyploma.app.ui.AppState;
import com.dyploma.app.ui.ConnectionView;
import com.dyploma.app.ui.SceneManager;
import javafx.geometry.Insets;
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
            String txt = String.format(
                    "Active connection: %s | %s %s:%d/%s (as %s)",
                    conn.name,
                    conn.dbType,
                    conn.host,
                    conn.port,
                    conn.database,
                    conn.username
            );
            connectionInfo = new Label(txt);
        } else {
            connectionInfo = new Label("No saved connection yet");
            connectionInfo.setStyle("-fx-opacity: 0.8;");
        }

        Button changeConnBtn = new Button("Change connection");
        changeConnBtn.setOnAction(e -> sceneManager.switchTo(new ConnectionView(sceneManager).build(), "Connection"));

        Button aiChatBtn = new Button("Open AI Chat");
        Button sqlBtn = new Button("Open SQL Console (later)");
        sqlBtn.setDisable(true);

        Button logoutBtn = new Button("Logout");

        aiChatBtn.setOnAction(e -> {
            // next step later
            System.out.println("TODO: open AI chat");
        });

        logoutBtn.setOnAction(e -> {
            AppState.setCurrentUser(null);
            // Повертаємось на екран автентифікації
            sceneManager.switchToAuth();
        });

        VBox root = new VBox(12, title, welcome, connectionInfo, changeConnBtn, aiChatBtn, sqlBtn, logoutBtn);
        root.setPadding(new Insets(16));
        root.setMaxWidth(720);

        return root;
    }
}
