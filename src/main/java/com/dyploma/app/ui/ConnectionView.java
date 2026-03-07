package com.dyploma.app.ui;

import com.dyploma.app.dao.ConnectionDao;
import com.dyploma.app.model.User;
import com.dyploma.app.service.DbTestService;
import com.dyploma.app.ui.dashboard.DashboardView;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class ConnectionView {

    private final SceneManager sceneManager;

    public ConnectionView(SceneManager sceneManager) {
        this.sceneManager = sceneManager;
    }

    public VBox build() {
        Label title = new Label("Database connection");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        // поля профілю
        TextField profileName = new TextField();
        ComboBox<String> dbType = new ComboBox<>();
        dbType.getItems().addAll("MYSQL", "POSTGRES");
        dbType.setValue("MYSQL");

        // поля підключення
        TextField host = new TextField("localhost");
        TextField port = new TextField("3306");
        TextField database = new TextField();
        TextField username = new TextField();
        PasswordField password = new PasswordField();

        // форма
        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.setPadding(new Insets(10));

        int r = 0;
        form.add(new Label("Profile name:"), 0, r); form.add(profileName, 1, r++);
        form.add(new Label("DB type:"), 0, r);      form.add(dbType, 1, r++);

        form.add(new Label("Host:"), 0, r);         form.add(host, 1, r++);
        form.add(new Label("Port:"), 0, r);         form.add(port, 1, r++);
        form.add(new Label("Database:"), 0, r);     form.add(database, 1, r++);
        form.add(new Label("Username:"), 0, r);     form.add(username, 1, r++);
        form.add(new Label("Password:"), 0, r);     form.add(password, 1, r++);

        Button testBtn = new Button("Test connection");
        Button saveBtn = new Button("Save");
        saveBtn.setDisable(true);

        Label status = new Label("Status: waiting…");

        testBtn.setOnAction(e -> {
            try {
                String type = dbType.getValue();
                String h = host.getText().trim();
                String db = database.getText().trim();
                String u = username.getText().trim();
                String p = password.getText();

                int prt;
                try {
                    prt = Integer.parseInt(port.getText().trim());
                } catch (Exception ex) {
                    status.setText("Status: ❌ port must be a number");
                    saveBtn.setDisable(true);
                    return;
                }

                if (h.isBlank() || db.isBlank() || u.isBlank()) {
                    status.setText("Status: ❌ fill host, database, username");
                    saveBtn.setDisable(true);
                    return;
                }

                status.setText("Status: ⏳ connecting...");
                new DbTestService().testConnection(type, h, prt, db, u, p);

                status.setText("Status: ✅ connection OK");
                saveBtn.setDisable(false);

            } catch (Exception ex) {
                status.setText("Status: ❌ " + ex.getMessage());
                saveBtn.setDisable(true);
            }
        });

        saveBtn.setOnAction(e -> {
            try {
                User user = AppState.getCurrentUser();
                if (user == null) {
                    status.setText("Status: ❌ no logged-in user");
                    return;
                }

                String name = profileName.getText().trim();
                String type = dbType.getValue();
                String h = host.getText().trim();
                String db = database.getText().trim();
                String u = username.getText().trim();
                String p = password.getText();

                int prt;
                try {
                    prt = Integer.parseInt(port.getText().trim());
                } catch (Exception ex) {
                    status.setText("Status: ❌ port must be a number");
                    return;
                }

                if (name.isBlank() || h.isBlank() || db.isBlank() || u.isBlank() || p.isBlank()) {
                    status.setText("Status: ❌ fill all fields");
                    return;
                }

                new ConnectionDao().insertConnection(
                        user.getId(),
                        name,
                        type,
                        h,
                        prt,
                        db,
                        u,
                        p
                );

                status.setText("Status: ✅ saved to local DB");

                // ✅ ПЕРЕХІД НА DASHBOARD
                sceneManager.switchTo(new DashboardView(sceneManager).build(), "Dashboard");

            } catch (Exception ex) {
                status.setText("Status: ❌ " + ex.getMessage());
            }
        });

        HBox buttons = new HBox(10, testBtn, saveBtn);
        VBox root = new VBox(12, title, form, buttons, status);
        root.setPadding(new Insets(16));
        root.setMaxWidth(520);

        return root;
    }
}
