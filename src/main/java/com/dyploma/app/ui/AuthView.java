package com.dyploma.app.ui;

import com.dyploma.app.model.User;
import com.dyploma.app.service.AuthService;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

public class AuthView {
    private final AuthService authService = new AuthService();

    public Parent build(Consumer<User> onSuccess) {
        Label title = new Label("Dyploma App");
        AppTheme.styleTitle(title);

        Label subtitle = new Label("Sign in to continue or create a new account in a cleaner, calmer workspace.");
        AppTheme.styleSubtitle(subtitle);

        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("app-tabs");
        tabs.getTabs().add(new Tab("Login", buildLogin(onSuccess)));
        tabs.getTabs().add(new Tab("Register", buildRegister(onSuccess)));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setTabMinWidth(120);

        VBox hero = new VBox(6, title, subtitle);
        VBox content = new VBox(20, hero, tabs);
        content.setPadding(new Insets(4));
        content.setPrefWidth(680);
        content.setMaxWidth(720);
        return AppTheme.wrap(content);
    }

    private VBox buildLogin(Consumer<User> onSuccess) {
        TextField username = new TextField();
        username.setPromptText("Username");
        AppTheme.styleField(username);

        PasswordField password = new PasswordField();
        password.setPromptText("Password");
        AppTheme.styleField(password);

        Label status = new Label("Enter your existing account details to continue.");
        AppTheme.styleStatus(status);

        Button login = new Button("Login");
        AppTheme.stylePrimaryButton(login);
        login.setOnAction(e -> {
            try {
                User user = authService.login(username.getText(), password.getText());
                status.setText("Logged in as " + user.getUsername());
                onSuccess.accept(user);
            } catch (Exception ex) {
                status.setText(ex.getMessage());
            }
        });

        GridPane form = formGrid();
        int r = 0;
        form.add(new Label("Username:"), 0, r); form.add(username, 1, r++);
        form.add(new Label("Password:"), 0, r); form.add(password, 1, r++);

        VBox box = new VBox(14, form, login, status);
        box.setPadding(new Insets(6, 0, 0, 0));
        box.setFillWidth(true);
        return box;
    }

    private VBox buildRegister(Consumer<User> onSuccess) {
        TextField username = new TextField();
        username.setPromptText("Choose a username");
        AppTheme.styleField(username);

        PasswordField password = new PasswordField();
        password.setPromptText("Create a password");
        AppTheme.styleField(password);

        PasswordField confirm = new PasswordField();
        confirm.setPromptText("Repeat password");
        AppTheme.styleField(confirm);

        Label status = new Label("Create an account and jump straight into the app.");
        AppTheme.styleStatus(status);

        Button register = new Button("Register");
        AppTheme.stylePrimaryButton(register);
        register.setOnAction(e -> {
            try {
                User user = authService.register(username.getText(), password.getText(), confirm.getText());
                status.setText("Registered as " + user.getUsername());
                onSuccess.accept(user);
            } catch (Exception ex) {
                status.setText(ex.getMessage());
            }
        });

        GridPane form = formGrid();
        int r = 0;
        form.add(new Label("Username:"), 0, r); form.add(username, 1, r++);
        form.add(new Label("Password:"), 0, r); form.add(password, 1, r++);
        form.add(new Label("Confirm:"), 0, r); form.add(confirm, 1, r++);

        Label hint = new Label("Password: minimum 6 characters");
        AppTheme.styleHint(hint);

        VBox box = new VBox(14, form, hint, register, status);
        box.setPadding(new Insets(6, 0, 0, 0));
        box.setFillWidth(true);
        return box;
    }

    private GridPane formGrid() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(10);

        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(110);

        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);

        g.getColumnConstraints().setAll(labels, fields);
        return g;
    }
}
