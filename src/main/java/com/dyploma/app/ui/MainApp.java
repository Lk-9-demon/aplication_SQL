package com.dyploma.app.ui;

import javafx.application.Application;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

public class MainApp extends Application {

    @Override
    public void start(Stage stage) {
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        stage.setMinWidth(Math.min(1180, bounds.getWidth() * 0.78));
        stage.setMinHeight(Math.min(820, bounds.getHeight() * 0.76));
        stage.setWidth(Math.min(1460, bounds.getWidth() * 0.9));
        stage.setHeight(Math.min(960, bounds.getHeight() * 0.9));
        stage.setResizable(true);
        stage.centerOnScreen();

        SceneManager sceneManager = new SceneManager(stage);
        sceneManager.switchToAuth();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
