package com.dyploma.app.ui;

import com.dyploma.app.dao.ConnectionDao;
import com.dyploma.app.model.User;
import com.dyploma.app.service.DbTestService;
import com.dyploma.app.ui.dashboard.DashboardView;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ConnectionView {

    private final SceneManager sceneManager;

    public ConnectionView(SceneManager sceneManager) {
        this.sceneManager = sceneManager;
    }

    public Parent build() {
        Label title = new Label("Database connection");
        AppTheme.styleTitle(title);

        Label subtitle = new Label("Create a polished connection profile, choose the database type, and verify access before saving it.");
        AppTheme.styleSubtitle(subtitle);

        TextField profileName = new TextField();
        profileName.setPromptText("For example: primary warehouse");
        AppTheme.styleField(profileName);

        ComboBox<String> dbType = new ComboBox<>();
        dbType.getItems().addAll("MYSQL", "POSTGRES", "SQLITE");
        dbType.setValue("MYSQL");
        AppTheme.styleField(dbType);

        TextField host = new TextField("localhost");
        AppTheme.styleField(host);

        TextField port = new TextField("3306");
        AppTheme.styleField(port);

        TextField database = new TextField();
        database.setPromptText("Database name");
        AppTheme.styleField(database);

        TextField username = new TextField();
        username.setPromptText("Username");
        AppTheme.styleField(username);

        PasswordField password = new PasswordField();
        password.setPromptText("Password");
        AppTheme.styleField(password);

        TextField sqlitePath = new TextField();
        sqlitePath.setPromptText("Default will be used if empty");
        AppTheme.styleField(sqlitePath);

        GridPane form = formGrid();
        form.setPadding(new Insets(12, 4, 12, 4));

        int r = 0;
        Label profileNameLbl = new Label("Profile name:");
        Label dbTypeLbl = new Label("DB type:");
        form.add(profileNameLbl, 0, r); form.add(profileName, 1, r++);
        form.add(dbTypeLbl, 0, r);      form.add(dbType, 1, r++);

        Label hostLbl = new Label("Host:");
        Label portLbl = new Label("Port:");
        Label databaseLbl = new Label("Database:");
        Label usernameLbl = new Label("Username:");
        Label passwordLbl = new Label("Password:");
        form.add(hostLbl, 0, r);         form.add(host, 1, r++);
        form.add(portLbl, 0, r);         form.add(port, 1, r++);
        form.add(databaseLbl, 0, r);     form.add(database, 1, r++);
        form.add(usernameLbl, 0, r);     form.add(username, 1, r++);
        form.add(passwordLbl, 0, r);     form.add(password, 1, r++);
        Label sqlitePathLbl = new Label("SQLite file path:");

        Button browseBtn = new Button("Browse...");
        AppTheme.styleSecondaryButton(browseBtn);

        Button findBtn = new Button("Find");
        AppTheme.styleSecondaryButton(findBtn);

        HBox sqlitePathBox = new HBox(8, sqlitePath, browseBtn, findBtn);
        HBox.setHgrow(sqlitePath, Priority.ALWAYS);
        form.add(sqlitePathLbl, 0, r); form.add(sqlitePathBox, 1, r++);

        Button testBtn = new Button("Test connection");
        AppTheme.styleSecondaryButton(testBtn);

        Button saveBtn = new Button("Save");
        AppTheme.stylePrimaryButton(saveBtn);
        saveBtn.setDisable(true);

        Label status = new Label("Status: waiting for a connection test.");
        AppTheme.styleStatus(status);

        Runnable toggleFields = () -> {
            boolean isSqlite = "SQLITE".equalsIgnoreCase(dbType.getValue());
            host.setManaged(!isSqlite); host.setVisible(!isSqlite);
            hostLbl.setManaged(!isSqlite); hostLbl.setVisible(!isSqlite);

            port.setManaged(!isSqlite); port.setVisible(!isSqlite);
            portLbl.setManaged(!isSqlite); portLbl.setVisible(!isSqlite);

            database.setManaged(!isSqlite); database.setVisible(!isSqlite);
            databaseLbl.setManaged(!isSqlite); databaseLbl.setVisible(!isSqlite);

            username.setManaged(!isSqlite); username.setVisible(!isSqlite);
            usernameLbl.setManaged(!isSqlite); usernameLbl.setVisible(!isSqlite);

            password.setManaged(!isSqlite); password.setVisible(!isSqlite);
            passwordLbl.setManaged(!isSqlite); passwordLbl.setVisible(!isSqlite);

            sqlitePathBox.setManaged(isSqlite); sqlitePathBox.setVisible(isSqlite);
            sqlitePathLbl.setManaged(isSqlite); sqlitePathLbl.setVisible(isSqlite);
        };
        toggleFields.run();
        dbType.setOnAction(ev -> toggleFields.run());

        browseBtn.setOnAction(ev -> {
            File file = chooseSqliteFile();
            if (file != null) {
                sqlitePath.setText(file.getAbsolutePath());
            }
        });

        findBtn.setOnAction(ev -> {
            List<File> found = findLocalDbFiles(200);
            if (found.isEmpty()) {
                Alert a = new Alert(Alert.AlertType.INFORMATION, "No local *.db files were found in the scanned folders.");
                a.setHeaderText(null);
                a.showAndWait();
                return;
            }
            ChoiceDialog<String> dlg = new ChoiceDialog<>();
            dlg.setTitle("Found local databases");
            dlg.setHeaderText("Choose the SQLite file");
            List<String> opts = found.stream().map(File::getAbsolutePath).collect(Collectors.toList());
            dlg.getItems().setAll(opts);
            if (!opts.isEmpty()) {
                dlg.setSelectedItem(opts.get(0));
            }
            dlg.showAndWait().ifPresent(sqlitePath::setText);
        });

        testBtn.setOnAction(e -> {
            try {
                String type = dbType.getValue();
                if ("SQLITE".equalsIgnoreCase(type)) {
                    String path = sqlitePath.getText().trim();
                    if (path.isBlank()) {
                        path = getDefaultSqlitePath(profileName.getText().trim());
                        sqlitePath.setText(path);
                    }
                    status.setText("Status: opening SQLite DB...");
                    new DbTestService().testSqlite(path);
                } else {
                    String h = host.getText().trim();
                    String db = database.getText().trim();
                    String u = username.getText().trim();
                    String p = password.getText();

                    int prt;
                    try {
                        prt = Integer.parseInt(port.getText().trim());
                    } catch (Exception ex) {
                        status.setText("Status: port must be a number");
                        saveBtn.setDisable(true);
                        return;
                    }

                    if (h.isBlank() || db.isBlank() || u.isBlank()) {
                        status.setText("Status: fill host, database, username");
                        saveBtn.setDisable(true);
                        return;
                    }

                    status.setText("Status: connecting...");
                    new DbTestService().testConnection(type, h, prt, db, u, p);
                }

                status.setText("Status: connection OK");
                saveBtn.setDisable(false);

            } catch (Exception ex) {
                status.setText("Status: " + ex.getMessage());
                saveBtn.setDisable(true);
            }
        });

        saveBtn.setOnAction(e -> {
            try {
                User user = AppState.getCurrentUser();
                if (user == null) {
                    status.setText("Status: no logged-in user");
                    return;
                }

                String name = profileName.getText().trim();
                String type = dbType.getValue();
                if (name.isBlank()) {
                    status.setText("Status: profile name is required");
                    return;
                }

                if ("SQLITE".equalsIgnoreCase(type)) {
                    String path = sqlitePath.getText().trim();
                    if (path.isBlank()) {
                        path = getDefaultSqlitePath(name);
                        sqlitePath.setText(path);
                    }
                    ensureParentDir(path);

                    new ConnectionDao().insertConnection(
                            user.getId(),
                            name,
                            type,
                            "",
                            0,
                            "",
                            "",
                            "",
                            path
                    );
                } else {
                    String h = host.getText().trim();
                    String db = database.getText().trim();
                    String u = username.getText().trim();
                    String p = password.getText();

                    int prt;
                    try {
                        prt = Integer.parseInt(port.getText().trim());
                    } catch (Exception ex) {
                        status.setText("Status: port must be a number");
                        return;
                    }

                    if (h.isBlank() || db.isBlank() || u.isBlank() || p.isBlank()) {
                        status.setText("Status: fill all fields");
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
                            p,
                            null
                    );
                }

                status.setText("Status: saved to local DB");
                sceneManager.switchTo(new DashboardView(sceneManager).build(), "Dashboard");

            } catch (Exception ex) {
                status.setText("Status: " + ex.getMessage());
            }
        });

        HBox buttons = new HBox(10, testBtn, saveBtn);
        HBox.setHgrow(testBtn, Priority.ALWAYS);
        HBox.setHgrow(saveBtn, Priority.ALWAYS);
        VBox hero = new VBox(6, title, subtitle);
        VBox root = new VBox(20, hero, form, buttons, status);
        root.setPadding(new Insets(4));
        root.setPrefWidth(820);
        root.setMaxWidth(900);

        return AppTheme.wrap(root);
    }

    private static String getDefaultSqlitePath(String profileName) {
        String safe = (profileName == null || profileName.isBlank()) ? "default" : profileName.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        Path dir = Path.of(System.getProperty("user.home"), ".dyploma", "databases");
        return dir.resolve(safe + ".db").toString();
    }

    private static void ensureParentDir(String filePath) {
        try {
            Path p = Path.of(filePath).toAbsolutePath().getParent();
            if (p != null) {
                Files.createDirectories(p);
            }
        } catch (Exception ignore) {
        }
    }

    private File chooseSqliteFile() {
        try {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle("Choose SQLite file");
            fc.getExtensionFilters().addAll(
                    new javafx.stage.FileChooser.ExtensionFilter("SQLite DB", "*.db", "*.sqlite", "*.db3", "*.bd"),
                    new javafx.stage.FileChooser.ExtensionFilter("All files", "*.*")
            );
            File initialDir = new File(System.getProperty("user.home"));
            if (initialDir.exists()) {
                fc.setInitialDirectory(initialDir);
            }
            return fc.showOpenDialog(null);
        } catch (Exception e) {
            return null;
        }
    }

    private List<File> findLocalDbFiles(int limit) {
        List<File> result = new ArrayList<>();
        List<File> roots = new ArrayList<>();
        roots.add(new File(System.getProperty("user.home")));

        File docs = new File(System.getProperty("user.home"), "Documents");
        if (docs.exists()) {
            roots.add(docs);
        }

        for (File root : roots) {
            walkFiles(root, result, limit);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private void walkFiles(File dir, List<File> acc, int limit) {
        if (dir == null || !dir.exists() || acc.size() >= limit) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (acc.size() >= limit) {
                return;
            }
            if (f.isDirectory()) {
                if (f.isHidden()) {
                    continue;
                }
                walkFiles(f, acc, limit);
            } else {
                String name = f.getName().toLowerCase();
                if (name.endsWith(".db") || name.endsWith(".sqlite") || name.endsWith(".db3") || name.endsWith(".bd")) {
                    acc.add(f);
                }
            }
        }
    }

    private GridPane formGrid() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(10);

        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(130);

        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);

        g.getColumnConstraints().setAll(labels, fields);
        return g;
    }
}
