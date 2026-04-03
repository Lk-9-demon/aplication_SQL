package com.dyploma.app.ui;

import com.dyploma.app.dao.ConnectionDao;
import com.dyploma.app.service.LocalAnalysisService;
import com.dyploma.app.service.LocalSqlSupport;
import com.dyploma.app.service.SchemaService;
import com.dyploma.app.service.SqlExecuteService;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;

public class LocalSqlGenView {

    private final SceneManager sceneManager;

    public LocalSqlGenView(SceneManager sceneManager) {
        this.sceneManager = sceneManager;
    }

    public VBox build() {
        LocalAnalysisService localAi = new LocalAnalysisService();

        Label title = new Label("Local AI Analyst (via Ollama)");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        SchemaService.SchemaInfo schema = AppState.getCurrentSchema();
        Label schemaHint = new Label(schema != null ? "Schema: loaded" : "Schema: not loaded (go to Dashboard -> Refresh schema)");
        String analysisModel = localAi.getConfiguredAnalysisModel();
        Label agentHint = new Label("Ollama SQL model: " + localAi.getConfiguredSqlModel() + " | Analysis model: " + analysisModel);
        agentHint.setStyle("-fx-opacity: 0.75;");

        TextArea question = new TextArea();
        question.setPromptText("Ask a question about your data. Ollama will generate SQL, run it locally, and summarize the result.");
        question.setPrefRowCount(4);

        TextArea sqlArea = new TextArea();
        sqlArea.setPromptText("Generated SQL will appear here...");
        sqlArea.setEditable(false);
        sqlArea.setPrefRowCount(5);

        TextArea analysisArea = new TextArea();
        analysisArea.setPromptText("Local AI explanation will appear here...");
        analysisArea.setEditable(false);
        analysisArea.setWrapText(true);
        analysisArea.setPrefRowCount(8);

        Button genBtn = new Button("Generate SQL");
        Button runBtn = new Button("Run + Analyze");
        runBtn.setDisable(true);
        Button backBtn = new Button("Back to Dashboard");

        Label status = new Label("Ready");

        TableView<List<Object>> table = new TableView<>();
        table.setPlaceholder(new Label("No data yet"));
        table.setMaxHeight(340);

        genBtn.setOnAction(e -> {
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

            sqlArea.clear();
            analysisArea.clear();
            table.getColumns().clear();
            table.getItems().clear();

            String sqlPrompt = LocalSqlSupport.buildSqlCoderPrompt(sc, q, null);
            try {
                System.out.println("[DEBUG_LOG] local sql prompt length: " + sqlPrompt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            } catch (Exception ignore) {
            }

            genBtn.setDisable(true);
            runBtn.setDisable(true);
            status.setText("Generating SQL via local model...");

            new Thread(() -> {
                try {
                    String system = "You translate natural language into SQL. Output SQL only.";
                    String userMsg = sqlPrompt;
                    String reply = localAi.chatWithSqlModel(system, userMsg);
                    try {
                        String prev = reply == null ? "" : reply.replaceAll("\n", " ");
                        if (prev.length() > 200) prev = prev.substring(0, 200) + "...";
                        System.out.println("[DEBUG_LOG] local toSql (first): " + prev);
                    } catch (Exception ignore) {
                    }

                    String candidate = reply == null ? "" : reply.trim();
                    LocalSqlSupport.ValidationResult validation = LocalSqlSupport.validateSqlAgainstSchema(candidate, sc);
                    if (!validation.isValid()) {
                        System.out.println("[DEBUG_LOG] local toSql validation issues: " + validation.formatMessage());
                        String retryUser = LocalSqlSupport.buildSqlCoderPrompt(sc, q, validation.formatMessage());
                        String retry = localAi.chatWithSqlModel(system, retryUser);
                        try {
                            String prev2 = retry == null ? "" : retry.replaceAll("\n", " ");
                            if (prev2.length() > 200) prev2 = prev2.substring(0, 200) + "...";
                            System.out.println("[DEBUG_LOG] local toSql (retry): " + prev2);
                        } catch (Exception ignore) {
                        }
                        LocalSqlSupport.ValidationResult retryValidation = LocalSqlSupport.validateSqlAgainstSchema(retry, sc);
                        if (retryValidation.isValid()) {
                            candidate = retry == null ? "" : retry.trim();
                            validation = retryValidation;
                        }
                    }

                    final String finalSql = candidate;
                    final LocalSqlSupport.ValidationResult finalValidation = LocalSqlSupport.validateSqlAgainstSchema(finalSql, sc);
                    javafx.application.Platform.runLater(() -> {
                        sqlArea.setText(finalSql);
                        if (!finalValidation.isValid()) {
                            analysisArea.setText("The SQL model returned a query that does not match the loaded schema. " + finalValidation.formatMessage());
                            status.setText("SQL generated, but validation failed");
                            genBtn.setDisable(false);
                            runBtn.setDisable(true);
                            return;
                        }
                        if (analysisModel != null && !analysisModel.isBlank()) {
                            analysisArea.setText("SQL is ready. Click 'Run + Analyze' to execute it locally and ask Ollama for an explanation.");
                        } else {
                            analysisArea.setText("SQL is ready. Execution will work, but natural-language local analysis needs LOCAL_AI_ANALYSIS_MODEL.");
                        }
                        status.setText("SQL generated");
                        genBtn.setDisable(false);
                        runBtn.setDisable(finalSql.isBlank());
                    });
                } catch (Exception ex1) {
                    javafx.application.Platform.runLater(() -> {
                        status.setText("Error: " + ex1.getMessage());
                        genBtn.setDisable(false);
                        runBtn.setDisable(true);
                    });
                }
            }, "local-sql-gen").start();
        });

        runBtn.setOnAction(e -> {
            String sql = sqlArea.getText();
            if (sql == null || sql.isBlank()) {
                status.setText("Nothing to run");
                return;
            }
            var user = AppState.getCurrentUser();
            if (user == null) {
                status.setText("Not logged in");
                return;
            }
            var conn = new ConnectionDao().findAnyForUser(user.getId());
            if (conn == null) {
                status.setText("No saved connection");
                return;
            }

            String q = question.getText();
            SchemaService.SchemaInfo sc = AppState.getCurrentSchema();
            LocalSqlSupport.ValidationResult validation = LocalSqlSupport.validateSqlAgainstSchema(sql, sc);
            if (!validation.isValid()) {
                status.setText("SQL validation failed");
                analysisArea.setText("The generated SQL does not match the loaded schema. " + validation.formatMessage());
                runBtn.setDisable(true);
                return;
            }

            genBtn.setDisable(true);
            runBtn.setDisable(true);
            analysisArea.setText("Running query locally and asking Ollama to analyze the result...");
            status.setText("Running locally and analyzing...");

            new Thread(() -> {
                try {
                    String clean = sql.trim();
                    if (clean.endsWith(";")) clean = clean.substring(0, clean.length() - 1).trim();

                    SqlExecuteService exec = new SqlExecuteService();
                    long count = -1L;
                    try {
                        count = exec.countRows(conn, clean, 15);
                    } catch (Exception ignore) {
                    }
                    var sample = exec.querySample(conn, clean, 1000, 20);

                    String explanation;
                    try {
                        explanation = localAi.explainQueryResult(q, sc, clean, sample.columns, sample.rows, count);
                    } catch (Exception exExplain) {
                        explanation = "Local analysis is unavailable right now: " + exExplain.getMessage();
                    }

                    final long fCount = count;
                    final SqlExecuteService.QueryResult fSample = sample;
                    final String fExplanation = explanation;
                    javafx.application.Platform.runLater(() -> {
                        renderTable(table, fSample.columns, fSample.rows);
                        analysisArea.setText(fExplanation);
                        String totalText = fCount >= 0 ? String.valueOf(fCount) : "unknown";
                        status.setText("Done (rows_shown=" + (fSample.rows == null ? 0 : fSample.rows.size()) + ", total=" + totalText + (fSample.truncated ? ", truncated" : "") + ")");
                        genBtn.setDisable(false);
                        runBtn.setDisable(false);
                    });
                } catch (Exception ex2) {
                    javafx.application.Platform.runLater(() -> {
                        status.setText("Error: " + ex2.getMessage());
                        analysisArea.setText("Execution failed before the local agent could analyze the result.");
                        genBtn.setDisable(false);
                        runBtn.setDisable(false);
                    });
                }
            }, "local-sql-run").start();
        });

        backBtn.setOnAction(e -> sceneManager.switchTo(new com.dyploma.app.ui.dashboard.DashboardView(sceneManager).build(), "Dashboard"));

        HBox btns = new HBox(10, genBtn, runBtn, backBtn);
        VBox root = new VBox(
                12,
                title,
                schemaHint,
                agentHint,
                new Label("Question"),
                question,
                new Label("Generated SQL"),
                sqlArea,
                btns,
                new Label("Result"),
                table,
                new Label("Local analysis"),
                analysisArea,
                status
        );
        root.setPadding(new Insets(16));
        root.setMaxWidth(980);
        return root;
    }

    private void renderTable(TableView<List<Object>> table, List<String> columns, List<List<Object>> rows) {
        table.getColumns().clear();
        table.getItems().clear();
        if (columns != null) {
            for (int i = 0; i < columns.size(); i++) {
                final int idx = i;
                TableColumn<List<Object>, Object> col = new TableColumn<>(columns.get(i));
                col.setCellValueFactory(cd -> {
                    List<Object> r = cd.getValue();
                    Object v = (r != null && idx < r.size()) ? r.get(idx) : null;
                    return new SimpleObjectProperty<>(v);
                });
                col.setPrefWidth(140);
                table.getColumns().add(col);
            }
        }
        if (rows != null) {
            table.setItems(FXCollections.observableArrayList(rows));
        }
    }
}
