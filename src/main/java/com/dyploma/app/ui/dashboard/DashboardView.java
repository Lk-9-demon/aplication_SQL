package com.dyploma.app.ui.dashboard;

import com.dyploma.app.dao.ConnectionDao;
import com.dyploma.app.model.User;
import com.dyploma.app.service.LocalAnalysisService;
import com.dyploma.app.service.SchemaService;
import com.dyploma.app.ui.AppTheme;
import com.dyploma.app.ui.AppState;
import com.dyploma.app.ui.ConnectionView;
import com.dyploma.app.ui.SceneManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

public class DashboardView {

    private final SceneManager sceneManager;

    public DashboardView(SceneManager sceneManager) {
        this.sceneManager = sceneManager;
    }

    public Parent build() {
        Label title = new Label("Dashboard");
        AppTheme.styleTitle(title);

        Label subtitle = new Label("Refresh the schema, launch the AI tools, and manage the current connection from one calmer workspace.");
        AppTheme.styleSubtitle(subtitle);

        User user = AppState.getCurrentUser();
        Label welcome = new Label(user != null ? ("Welcome, " + user.getUsername()) : "Welcome");
        AppTheme.styleLead(welcome);

        ConnectionDao.SavedConnection conn = null;
        if (user != null) {
            try {
                conn = new ConnectionDao().findAnyForUser(user.getId());
            } catch (Exception ignore) {
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
        }
        AppTheme.styleHint(connectionInfo);

        Label schemaStatus = new Label("Schema: not loaded");
        AppTheme.styleStatus(schemaStatus);

        Button refreshSchemaBtn = new Button("Refresh schema");
        AppTheme.stylePrimaryButton(refreshSchemaBtn);

        Button changeConnBtn = new Button("Change connection");
        AppTheme.stylePrimaryButton(changeConnBtn);
        changeConnBtn.setOnAction(e -> sceneManager.switchTo(new ConnectionView(sceneManager).build(), "Connection"));

        Button aiChatBtn = new Button("Open AI Data Chat");
        AppTheme.stylePrimaryButton(aiChatBtn);
        aiChatBtn.setDisable(true);

        Button openLocalSqlBtn = new Button("Open Local Analyst");
        AppTheme.stylePrimaryButton(openLocalSqlBtn);
        openLocalSqlBtn.setOnAction(e -> sceneManager.switchTo(new com.dyploma.app.ui.LocalSqlGenView(sceneManager).build(), "Local Analyst"));

        Button sqlBtn = new Button("Open SQL Console (coming soon)");
        AppTheme.styleSecondaryButton(sqlBtn);
        sqlBtn.setDisable(true);

        Button logoutBtn = new Button("Logout");
        AppTheme.styleDangerButton(logoutBtn);

        Button testLocalModelBtn = new Button("Test Ollama");
        AppTheme.stylePrimaryButton(testLocalModelBtn);
        testLocalModelBtn.setOnAction(e -> {
            testLocalModelBtn.setDisable(true);
            Label progress = new Label("Local AI: calling...");
            Alert a = new Alert(Alert.AlertType.INFORMATION);
            a.setHeaderText(null);
            a.setTitle("Local AI Test");
            a.getDialogPane().setContent(progress);
            a.show();

            new Thread(() -> {
                try {
                    LocalAnalysisService localAi = new LocalAnalysisService();
                    String system = "You are a local data analysis assistant. Answer concisely.";
                    String userMsg = "Briefly confirm that the local analysis model is ready.";
                    String reply = localAi.chatWithAnalysisModel(system, userMsg);
                    javafx.application.Platform.runLater(() -> {
                        a.setContentText("Reply:\n" + reply);
                        progress.setText("Reply:\n" + reply);
                        testLocalModelBtn.setDisable(false);
                    });
                } catch (Exception ex) {
                    javafx.application.Platform.runLater(() -> {
                        a.setAlertType(Alert.AlertType.ERROR);
                        a.setContentText("Error: " + ex.getMessage());
                        progress.setText("Error: " + ex.getMessage());
                        testLocalModelBtn.setDisable(false);
                    });
                }
            }, "local-ai-test").start();
        });

        aiChatBtn.setOnAction(e -> sceneManager.switchTo(new com.dyploma.app.ui.AiChatView(sceneManager).build(), "AI Data Chat"));

        logoutBtn.setOnAction(e -> {
            AppState.setCurrentUser(null);
            AppState.setCurrentSchema(null);
            sceneManager.switchToAuth();
        });

        if (AppState.getCurrentSchema() != null) {
            schemaStatus.setText("Schema: loaded");
            aiChatBtn.setDisable(false);
        }

        ConnectionDao.SavedConnection finalConn = conn;
        refreshSchemaBtn.setOnAction(e -> doRefreshSchema(user, finalConn, schemaStatus, aiChatBtn));

        if (AppState.getCurrentSchema() == null && conn != null && user != null) {
            doRefreshSchema(user, conn, schemaStatus, aiChatBtn);
        }

        VBox workspaceCard = new VBox(
                8,
                sectionTitle("Workspace"),
                welcome,
                connectionInfo,
                schemaStatus
        );
        AppTheme.styleSection(workspaceCard);

        VBox toolsCard = new VBox(
                10,
                sectionTitle("Tools"),
                refreshSchemaBtn,
                aiChatBtn,
                openLocalSqlBtn,
                testLocalModelBtn
        );
        AppTheme.styleSection(toolsCard);

        VBox settingsCard = new VBox(
                10,
                sectionTitle("Connection & account"),
                changeConnBtn,
                sqlBtn,
                logoutBtn
        );
        AppTheme.styleSection(settingsCard);

        VBox hero = new VBox(6, title, subtitle);
        VBox root = new VBox(18, hero, workspaceCard, toolsCard, settingsCard);
        root.setPadding(new Insets(4));
        root.setPrefWidth(900);
        root.setMaxWidth(980);

        return AppTheme.wrap(root);
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
        long startedAt = System.currentTimeMillis();
        schemaStatus.setText("Schema: loading...");
        aiChatBtn.setDisable(true);

        new Thread(() -> {
            try {
                SchemaService ss = new SchemaService();
                System.out.println("[DEBUG_LOG] Schema refresh started for user=" + user.getId()
                        + ", connectionName=" + conn.name + ", type=" + conn.dbType);
                ss.refresh(user.getId(), conn);
                var schema = AppState.getCurrentSchema();
                long tookMs = System.currentTimeMillis() - startedAt;
                Platform.runLater(() -> {
                    if (schema != null && schema.tables != null) {
                        int tables = schema.tables.size();
                        schemaStatus.setText("Schema: loaded (" + tables + " tables, " + tookMs + " ms, saved to file)");
                        aiChatBtn.setDisable(tables == 0);
                    } else {
                        schemaStatus.setText("Schema: error - empty schema returned");
                        aiChatBtn.setDisable(true);
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    schemaStatus.setText("Schema: error - " + ex.getMessage());
                    aiChatBtn.setDisable(true);
                });
            }
        }, "schema-refresh").start();
    }

    private Label sectionTitle(String text) {
        Label label = new Label(text);
        AppTheme.styleSectionTitle(label);
        return label;
    }
}
