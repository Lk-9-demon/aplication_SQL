package com.dyploma.app.ui;

import com.dyploma.app.dao.ConnectionDao;
import com.dyploma.app.model.User;
import com.dyploma.app.service.AiService;
import com.dyploma.app.service.SchemaService;
import com.dyploma.app.service.SqlExecuteService;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class AiChatView {

    private final SceneManager sceneManager;

    public AiChatView(SceneManager sceneManager) {
        this.sceneManager = sceneManager;
    }

    public Parent build() {
        Label title = new Label("AI Data Chat");
        AppTheme.styleTitle(title);

        Label subtitle = new Label("Ask direct data questions, review a clean response, and inspect structured results without losing context.");
        AppTheme.styleSubtitle(subtitle);

        SchemaService.SchemaInfo schema = AppState.getCurrentSchema();
        Label schemaHint = new Label(schema != null ? "Schema: loaded" : "Schema: not loaded (go to Dashboard -> Refresh schema)");
        AppTheme.styleHint(schemaHint);

        TextArea chatHistory = new TextArea();
        chatHistory.setEditable(false);
        chatHistory.setWrapText(true);
        chatHistory.setPrefRowCount(16);
        AppTheme.styleField(chatHistory);
        chatHistory.setText("""
Assistant: Use this chat when you want to inspect data from the database directly.

Examples: top 5 albums sold, most expensive track, customers from the USA, or best-selling tracks.
""".trim());

        TextArea question = new TextArea();
        question.setPromptText("Ask a direct data question, for example: top 5 albums sold or most expensive track...");
        question.setPrefRowCount(4);
        AppTheme.styleField(question);

        Button askBtn = new Button("Ask");
        AppTheme.stylePrimaryButton(askBtn);

        Button backBtn = new Button("Back to Dashboard");
        AppTheme.styleSecondaryButton(backBtn);
        askBtn.setDisable(schema == null);

        Label status = new Label("Ready");
        AppTheme.styleStatus(status);

        TableView<java.util.List<Object>> table = new TableView<>();
        Label tablePlaceholder = new Label("No tabular result yet");
        AppTheme.styleHint(tablePlaceholder);
        table.setPlaceholder(tablePlaceholder);
        table.setMaxHeight(320);

        askBtn.setOnAction(e -> {
            String q = question.getText();
            if (q == null || q.isBlank()) {
                status.setText("Enter a question first");
                return;
            }
            SchemaService.SchemaInfo sc = AppState.getCurrentSchema();
            if (sc == null) {
                status.setText("Schema is not loaded - go to Dashboard and click 'Refresh schema'");
                return;
            }

            User user = AppState.getCurrentUser();
            if (user == null) {
                status.setText("Not logged in");
                return;
            }
            ConnectionDao.SavedConnection conn = new ConnectionDao().findAnyForUser(user.getId());
            if (conn == null) {
                status.setText("No saved connection");
                return;
            }

            appendChat(chatHistory, "user", q);
            status.setText("Thinking... (data lookup)");
            askBtn.setDisable(true);

            new Thread(() -> {
                try {
                    AiService ai = new AiService();
                    String sql = ai.toSql(q, sc);
                    try {
                        String preview = sql == null ? "" : sql.replaceAll("\n", " ");
                        if (preview.length() > 200) {
                            preview = preview.substring(0, 200) + "...";
                        }
                        System.out.println("[DEBUG_LOG] toSql (first): " + preview);
                    } catch (Exception ignore) {
                    }

                    SqlExecuteService exec = new SqlExecuteService();
                    long rowCount = -1L;
                    boolean countOk = false;
                    try {
                        String sqlClean = sql != null ? sql.trim() : "";
                        if (sqlClean.endsWith(";")) {
                            sqlClean = sqlClean.substring(0, sqlClean.length() - 1).trim();
                        }
                        rowCount = exec.countRows(conn, sqlClean, 15);
                        countOk = true;
                        System.out.println("[DEBUG_LOG] countRows OK (first try): " + rowCount);
                    } catch (Exception exCnt) {
                        System.out.println("[DEBUG_LOG] countRows FAILED (first try): " + exCnt.getMessage());
                        try {
                            String retrySql = ai.toSqlRetryNamesOnly(q, sc, exCnt.getMessage());
                            try {
                                String retryPreview = retrySql == null ? "" : retrySql.replaceAll("\n", " ");
                                if (retryPreview.length() > 200) {
                                    retryPreview = retryPreview.substring(0, 200) + "...";
                                }
                                System.out.println("[DEBUG_LOG] toSql (retry): " + retryPreview);
                            } catch (Exception ignore) {
                            }
                            String retryClean = retrySql != null ? retrySql.trim() : "";
                            if (retryClean.endsWith(";")) {
                                retryClean = retryClean.substring(0, retryClean.length() - 1).trim();
                            }
                            rowCount = exec.countRows(conn, retryClean, 15);
                            sql = retryClean;
                            countOk = true;
                            System.out.println("[DEBUG_LOG] countRows OK (retry): " + rowCount);
                        } catch (Exception exRetry) {
                            System.out.println("[DEBUG_LOG] countRows FAILED (retry): " + exRetry.getMessage());
                        }
                    }

                    int sampleLimit = detectTopN(q);
                    if (sampleLimit <= 0) {
                        sampleLimit = 1000;
                    }

                    SqlExecuteService.QueryResult sample = null;
                    try {
                        sample = new SqlExecuteService().querySample(conn, sql, sampleLimit, 15);
                    } catch (Exception exSample) {
                        System.out.println("[DEBUG_LOG] querySample FAILED: " + exSample.getMessage());
                    }

                    StringBuilder mtx = new StringBuilder();
                    if (countOk) {
                        mtx.append("row_count=").append(rowCount);
                    }
                    if (sample != null && sample.columns != null && !sample.columns.isEmpty()) {
                        if (mtx.length() > 0) {
                            mtx.append("; ");
                        }
                        mtx.append("columns=").append(String.join(",", sample.columns));
                        if (mtx.length() > 0) {
                            mtx.append("; ");
                        }
                        mtx.append("rows_shown=").append(sample.rows != null ? sample.rows.size() : 0);
                    }
                    String metrics = mtx.length() == 0 ? null : mtx.toString();
                    try {
                        System.out.println("[DEBUG_LOG] toExplanation metrics: " + (metrics == null ? "<none>" : metrics));
                    } catch (Exception ignore) {
                    }

                    String explanation = ai.toExplanationWithMetrics(q, sc, sql, metrics);

                    final SqlExecuteService.QueryResult fSample = sample;
                    final java.util.List<String> fCols = fSample != null ? fSample.columns : null;
                    final java.util.List<java.util.List<Object>> fRows = fSample != null ? fSample.rows : null;
                    final String fExplanation = explanation;

                    javafx.application.Platform.runLater(() -> {
                        appendChat(chatHistory, "assistant", fExplanation);
                        if (fCols != null && !fCols.isEmpty()) {
                            renderTable(table, fCols, fRows);
                        }
                        status.setText("Done");
                        askBtn.setDisable(false);
                        question.clear();
                    });
                } catch (Exception ex) {
                    javafx.application.Platform.runLater(() -> {
                        appendChat(chatHistory, "assistant", "Error: " + ex.getMessage());
                        status.setText("Error");
                        askBtn.setDisable(false);
                    });
                }
            }, "ai-chat").start();
        });

        backBtn.setOnAction(e -> sceneManager.switchTo(new com.dyploma.app.ui.dashboard.DashboardView(sceneManager).build(), "Dashboard"));

        HBox buttons = new HBox(10, askBtn, backBtn);
        HBox.setHgrow(askBtn, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(backBtn, javafx.scene.layout.Priority.ALWAYS);
        VBox hero = new VBox(6, title, subtitle);
        VBox root = new VBox(18, hero, schemaHint, chatHistory, table, question, buttons, status);
        root.setPadding(new Insets(4));
        root.setPrefWidth(1120);
        root.setMaxWidth(1200);

        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                javafx.application.Platform.runLater(() -> {
                    SchemaService.SchemaInfo scNow = AppState.getCurrentSchema();
                    askBtn.setDisable(scNow == null);
                });
            }
        });
        return AppTheme.wrap(root);
    }

    private void renderTable(TableView<java.util.List<Object>> table,
                             java.util.List<String> columns,
                             java.util.List<java.util.List<Object>> rows) {
        table.getColumns().clear();
        table.getItems().clear();
        if (columns == null || columns.isEmpty()) {
            return;
        }
        for (int i = 0; i < columns.size(); i++) {
            final int colIndex = i;
            TableColumn<java.util.List<Object>, Object> col = new TableColumn<>(columns.get(i));
            col.setCellValueFactory(cd -> {
                java.util.List<Object> r = cd.getValue();
                Object v = (r != null && colIndex < r.size()) ? r.get(colIndex) : null;
                return new SimpleObjectProperty<>(v);
            });
            col.setPrefWidth(140);
            table.getColumns().add(col);
        }
        if (rows != null) {
            table.setItems(FXCollections.observableArrayList(rows));
        }
    }

    private int detectTopN(String question) {
        if (question == null) {
            return -1;
        }
        String q = question.toLowerCase();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("top[- ]?(\\d+)").matcher(q);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignore) {
            }
        }
        m = java.util.regex.Pattern.compile("first[ ]+(\\d+)").matcher(q);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignore) {
            }
        }
        m = java.util.regex.Pattern.compile("РЅР°Р№РєСЂР°С‰[С–i][ ]+(\\d+)|Р»СѓС‡С€РёРµ[ ]+(\\d+)").matcher(q);
        if (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                String g = m.group(i);
                if (g != null) {
                    try {
                        return Integer.parseInt(g);
                    } catch (Exception ignore) {
                    }
                }
            }
        }
        return -1;
    }

    private void appendChat(TextArea history, String role, String content) {
        String prefix = switch (role) {
            case "user" -> "You: ";
            case "assistant" -> "Assistant: ";
            default -> "";
        };
        String current = history.getText();
        if (current == null) {
            current = "";
        }
        if (!current.isBlank()) {
            current += "\n\n";
        }
        history.setText(current + prefix + content);
        history.positionCaret(history.getText().length());
    }
}
