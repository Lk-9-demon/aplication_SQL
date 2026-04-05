package com.dyploma.app.ui;

import com.dyploma.app.dao.ConnectionDao;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class SceneManager {

    private final Stage stage;

    public SceneManager(Stage stage) {
        this.stage = stage;
    }

    public void switchTo(Parent root, String title) {
        boolean wasShowing = stage.isShowing();
        boolean wasFullScreen = stage.isFullScreen();
        boolean wasMaximized = stage.isMaximized();
        double width = stage.getWidth();
        double height = stage.getHeight();
        double x = stage.getX();
        double y = stage.getY();

        Scene scene = width > 0 && height > 0
                ? new Scene(root, width, height)
                : new Scene(root);
        var stylesheet = SceneManager.class.getResource("/com/dyploma/app/ui/app-theme.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        stage.setScene(scene);
        stage.setTitle(title);

        if (!wasFullScreen && !wasMaximized && wasShowing && width > 0 && height > 0) {
            stage.setX(x);
            stage.setY(y);
            stage.setWidth(width);
            stage.setHeight(height);
        }

        if (!stage.isShowing()) {
            stage.show();
        }

        Platform.runLater(() -> {
            stage.setMaximized(wasMaximized);
            stage.setFullScreen(wasFullScreen);
        });
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
