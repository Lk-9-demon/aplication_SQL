package com.dyploma.app.ui;

import com.dyploma.app.dao.ConnectionDao;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class SceneManager {

    private final Stage stage;

    public SceneManager(Stage stage) {
        this.stage = stage;
    }

    public void switchTo(Parent root, String title) {
        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.setTitle(title);
        stage.show();
    }

    // Відкрити екран автентифікації і навігацію далі після успіху
    public void switchToAuth() {
        AuthView authView = new AuthView();
        switchTo(authView.build(user -> {
            AppState.setCurrentUser(user);
            // Якщо у користувача вже є збережене підключення — одразу на Dashboard,
            // інакше відкриваємо форму створення профілю підключення
            boolean hasConn = new ConnectionDao().hasAnyForUser(user.getId());
            if (hasConn) {
                switchTo(new com.dyploma.app.ui.dashboard.DashboardView(this).build(), "Dashboard");
            } else {
                switchTo(new ConnectionView(this).build(), "Connection");
            }
        }), "Auth");
    }
}
