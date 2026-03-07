package com.dyploma.app.ui;

import javafx.application.Application;
import javafx.stage.Stage;

public class MainApp extends Application {

    @Override
    public void start(Stage stage) {
        // Створюємо SceneManager і відкриваємо початковий екран (Auth)
        SceneManager sceneManager = new SceneManager(stage);
        sceneManager.switchToAuth();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
