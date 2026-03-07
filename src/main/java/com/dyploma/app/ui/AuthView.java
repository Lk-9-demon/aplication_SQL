package com.dyploma.app.ui;

import com.dyploma.app.model.User;
import com.dyploma.app.service.AuthService;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

public class AuthView {
    private final AuthService authService = new AuthService();

    public VBox build(Consumer<User> onSuccess) {
        Label title = new Label("Dyploma App – Login / Register");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        TabPane tabs = new TabPane();
        tabs.getTabs().add(new Tab("Login", buildLogin(onSuccess)));
        tabs.getTabs().add(new Tab("Register", buildRegister(onSuccess)));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        VBox root = new VBox(12, title, tabs);
        root.setPadding(new Insets(16));
        root.setMaxWidth(520);
        return root;
    }

    private VBox buildLogin(Consumer<User> onSuccess) {
        TextField username = new TextField();
        PasswordField password = new PasswordField();
        Label status = new Label();

        Button login = new Button("Login");
        login.setOnAction(e -> {
            try {
                User user = authService.login(username.getText(), password.getText());
                status.setText("✅ Logged in as " + user.getUsername());
                onSuccess.accept(user);
            } catch (Exception ex) {
                status.setText("❌ " + ex.getMessage());
            }
        });

        GridPane form = formGrid();
        int r = 0;
        form.add(new Label("Username:"), 0, r); form.add(username, 1, r++);
        form.add(new Label("Password:"), 0, r); form.add(password, 1, r++);

        return new VBox(10, form, login, status);
    }

    private VBox buildRegister(Consumer<User> onSuccess) {
        TextField username = new TextField();
        PasswordField password = new PasswordField();
        PasswordField confirm = new PasswordField();
        Label status = new Label();

        Button register = new Button("Register");
        register.setOnAction(e -> {
            try {
                User user = authService.register(username.getText(), password.getText(), confirm.getText());
                status.setText("✅ Registered as " + user.getUsername());
                onSuccess.accept(user); // авто-логін після реєстрації
            } catch (Exception ex) {
                status.setText("❌ " + ex.getMessage());
            }
        });

        GridPane form = formGrid();
        int r = 0;
        form.add(new Label("Username:"), 0, r); form.add(username, 1, r++);
        form.add(new Label("Password:"), 0, r); form.add(password, 1, r++);
        form.add(new Label("Confirm:"), 0, r); form.add(confirm, 1, r++);

        Label hint = new Label("Password: minimum 6 characters");
        hint.setStyle("-fx-opacity: 0.7;");

        return new VBox(10, form, hint, register, status);
    }

    private GridPane formGrid() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(10);
        return g;
    }
}
