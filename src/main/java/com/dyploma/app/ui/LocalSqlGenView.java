package com.dyploma.app.ui;

import com.dyploma.app.dao.ConnectionDao;
import com.dyploma.app.model.User;
import com.dyploma.app.service.AiService;
import com.dyploma.app.service.LocalAnalysisService;
import com.dyploma.app.service.LocalSqlSupport;
import com.dyploma.app.service.SchemaService;
import com.dyploma.app.service.SqlExecuteService;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class LocalSqlGenView {

    private final SceneManager sceneManager;

    public LocalSqlGenView(SceneManager sceneManager) {
        this.sceneManager = sceneManager;
    }

    public VBox build() {
        LocalAnalysisService localAi = new LocalAnalysisService();
        AiService plannerAi = new AiService();

        Label title = new Label("Private Local Analyst");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        Label subtitle = new Label("Ask analytical questions here. The app can use cloud planning without sending raw business data, then execute analysis locally and let the local model explain the result.");
        subtitle.setWrapText(true);

        SchemaService.SchemaInfo schema = AppState.getCurrentSchema();
        Label schemaHint = new Label(schema != null
                ? "Schema: loaded"
                : "Schema: not loaded (go to Dashboard -> Refresh schema)");

        Label agentHint = new Label("Private analysis model: " + localAi.getConfiguredAnalysisModel()
                + " | Planner: " + (plannerAi.isPlannerAvailable() ? "cloud schema-only" : "local fallback"));
        agentHint.setStyle("-fx-opacity: 0.75;");

        TextArea chatHistory = new TextArea();
        chatHistory.setEditable(false);
        chatHistory.setWrapText(true);
        chatHistory.setPrefRowCount(18);
        chatHistory.setText("""
Local analyst: Ask about trends, comparisons, growth, customer segments, seasonality, or simple forecasts.

If you want to inspect raw rows or ask direct data lookup questions like "top 5 albums" or "most expensive track", use AI Data Chat from the dashboard.
""".trim());

        TextArea question = new TextArea();
        question.setPromptText("Ask an analytical question, for example: Compare this month to last month, or estimate next month's revenue trend.");
        question.setPrefRowCount(4);

        Button askBtn = new Button("Analyze");
        askBtn.setDisable(schema == null);
        Button backBtn = new Button("Back to Dashboard");

        Label status = new Label("Ready");

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
            question.clear();
            askBtn.setDisable(true);
            status.setText("Preparing private analysis...");

            new Thread(() -> runLocalAnalysis(plannerAi, localAi, sc, conn, q, chatHistory, askBtn, status), "local-analyst-chat").start();
        });

        backBtn.setOnAction(e -> sceneManager.switchTo(new com.dyploma.app.ui.dashboard.DashboardView(sceneManager).build(), "Dashboard"));

        HBox buttons = new HBox(10, askBtn, backBtn);
        VBox root = new VBox(12, title, subtitle, schemaHint, agentHint, chatHistory, question, buttons, status);
        root.setPadding(new Insets(16));
        root.setMaxWidth(920);

        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                javafx.application.Platform.runLater(() -> askBtn.setDisable(AppState.getCurrentSchema() == null));
            }
        });

        return root;
    }

    private void runLocalAnalysis(AiService plannerAi,
                                  LocalAnalysisService localAi,
                                  SchemaService.SchemaInfo schema,
                                  ConnectionDao.SavedConnection conn,
                                  String question,
                                  TextArea chatHistory,
                                  Button askBtn,
                                  Label status) {
        try {
            String explanation;
            long totalEvidenceRows;

            if (plannerAi.isPlannerAvailable()) {
                statusOnUi(status, "Planning private analysis...");
                AiService.AnalysisPlan plan = buildAnalysisPlan(plannerAi, schema, question);
                java.util.List<LocalAnalysisService.AnalysisEvidence> evidences = executeAnalysisPlan(conn, schema, plan);
                totalEvidenceRows = evidences.stream().mapToInt(e -> e.rows == null ? 0 : e.rows.size()).sum();
                try {
                    explanation = localAi.explainPlannedAnalysis(question, schema, plan, evidences);
                } catch (Exception exExplain) {
                    explanation = "I executed the private analysis plan, but the local analyst could not summarize it yet: " + exExplain.getMessage();
                }
            } else {
                statusOnUi(status, "Running local fallback analysis...");
                String hiddenSql = buildHiddenSql(localAi, schema, question);
                LocalSqlSupport.ValidationResult validation = LocalSqlSupport.validateSqlAgainstSchema(hiddenSql, schema);
                if (!validation.isValid()) {
                    String reply = buildValidationMessage(validation);
                    javafx.application.Platform.runLater(() -> {
                        appendChat(chatHistory, "assistant", reply);
                        status.setText("Analysis needs a clearer question");
                        askBtn.setDisable(false);
                    });
                    return;
                }

                SqlExecuteService exec = new SqlExecuteService();
                String cleanSql = hiddenSql.trim();
                if (cleanSql.endsWith(";")) {
                    cleanSql = cleanSql.substring(0, cleanSql.length() - 1).trim();
                }

                long count = -1L;
                try {
                    count = exec.countRows(conn, cleanSql, 15);
                } catch (Exception ignore) {
                }

                SqlExecuteService.QueryResult sample = exec.querySample(conn, cleanSql, 1000, 20);
                totalEvidenceRows = sample.rows == null ? 0 : sample.rows.size();

                try {
                    explanation = localAi.explainQueryResult(question, schema, cleanSql, sample.columns, sample.rows, count);
                } catch (Exception exExplain) {
                    explanation = "I ran the private analysis query, but the local analyst could not summarize it yet: " + exExplain.getMessage();
                }
            }

            final String finalExplanation = explanation == null || explanation.isBlank()
                    ? "The private analysis pipeline finished, but the local analyst returned an empty answer. Try asking the same question with a clearer metric or time period."
                    : explanation;
            final long finalEvidenceRows = totalEvidenceRows;

            javafx.application.Platform.runLater(() -> {
                appendChat(chatHistory, "assistant", finalExplanation);
                status.setText("Done (rows_analyzed=" + finalEvidenceRows + ")");
                askBtn.setDisable(false);
            });
        } catch (Exception ex) {
            javafx.application.Platform.runLater(() -> {
                appendChat(chatHistory, "assistant", "I couldn't complete a trustworthy private analysis for that question yet. Try phrasing the metric and time period more explicitly.\n\nDetail: " + ex.getMessage());
                status.setText("Error");
                askBtn.setDisable(false);
            });
        }
    }

    private AiService.AnalysisPlan buildAnalysisPlan(AiService plannerAi,
                                                     SchemaService.SchemaInfo schema,
                                                     String question) throws Exception {
        AiService.AnalysisPlan firstPlan = plannerAi.planPrivateAnalysis(question, schema, null);
        String firstIssues = validatePlan(firstPlan, schema);
        if (firstIssues == null) {
            if (!needsSupportingCoverage(question, firstPlan)) {
                return firstPlan;
            }

            String coverageHint = "Add supporting evidence queries with explicit aliases like invoice_count or customer_count. Do not rely on one aggregate result row as coverage.";
            AiService.AnalysisPlan coveragePlan = plannerAi.planPrivateAnalysis(
                    question,
                    schema,
                    coverageHint
            );
            String coverageIssues = validatePlan(coveragePlan, schema);
            if (coverageIssues == null) {
                return coveragePlan;
            }
            firstIssues = coverageHint + " " + coverageIssues;
        }

        AiService.AnalysisPlan retryPlan = plannerAi.planPrivateAnalysis(question, schema, firstIssues);
        String retryIssues = validatePlan(retryPlan, schema);
        if (retryIssues == null) {
            if (!needsSupportingCoverage(question, retryPlan)) {
                return retryPlan;
            }

            String coverageRetryHint = "Add supporting evidence queries with explicit aliases like invoice_count or customer_count. Distinguish aggregate totals from source-record counts.";
            AiService.AnalysisPlan coverageRetryPlan = plannerAi.planPrivateAnalysis(question, schema, coverageRetryHint);
            String coverageRetryIssues = validatePlan(coverageRetryPlan, schema);
            if (coverageRetryIssues == null) {
                return coverageRetryPlan;
            }
            retryIssues = coverageRetryHint + " " + coverageRetryIssues;
        }

        throw new IllegalArgumentException("Planner could not build a schema-safe analysis plan. " + retryIssues);
    }

    private java.util.List<LocalAnalysisService.AnalysisEvidence> executeAnalysisPlan(ConnectionDao.SavedConnection conn,
                                                                                      SchemaService.SchemaInfo schema,
                                                                                      AiService.AnalysisPlan plan) throws Exception {
        SqlExecuteService exec = new SqlExecuteService();
        java.util.List<LocalAnalysisService.AnalysisEvidence> evidences = new java.util.ArrayList<>();
        for (AiService.AnalysisQuery query : plan.queries) {
            if (query == null || query.sql == null || query.sql.isBlank()) {
                continue;
            }

            String cleanSql = query.sql.trim();
            if (cleanSql.endsWith(";")) {
                cleanSql = cleanSql.substring(0, cleanSql.length() - 1).trim();
            }

            LocalSqlSupport.ValidationResult validation = LocalSqlSupport.validateSqlAgainstSchema(cleanSql, schema);
            if (!validation.isValid()) {
                throw new IllegalArgumentException("Planner query '" + safeLabel(query.purpose, query.id) + "' is invalid. " + validation.formatMessage());
            }

            long count = -1L;
            try {
                count = exec.countRows(conn, cleanSql, 15);
            } catch (Exception ignore) {
            }
            SqlExecuteService.QueryResult sample = exec.querySample(conn, cleanSql, 60, 20);

            LocalAnalysisService.AnalysisEvidence evidence = new LocalAnalysisService.AnalysisEvidence();
            evidence.id = query.id;
            evidence.purpose = query.purpose;
            evidence.sql = cleanSql;
            evidence.resultRowCount = count;
            evidence.columns = sample.columns;
            evidence.rows = sample.rows;
            evidence.truncated = sample.truncated;
            evidences.add(evidence);
        }

        if (evidences.isEmpty()) {
            throw new IllegalArgumentException("Planner returned no executable evidence");
        }
        return evidences;
    }

    private String buildHiddenSql(LocalAnalysisService localAi,
                                  SchemaService.SchemaInfo schema,
                                  String question) throws Exception {
        String system = "You translate analytical questions into SQL. Output SQL only.";
        String firstPrompt = LocalSqlSupport.buildSqlCoderPrompt(schema, question, null);
        String firstReply = localAi.chatWithSqlModel(system, firstPrompt);
        String candidate = LocalSqlSupport.normalizeSqlReply(firstReply);
        LocalSqlSupport.ValidationResult validation = LocalSqlSupport.validateSqlAgainstSchema(candidate, schema);
        if (validation.isValid()) {
            return candidate;
        }

        String retryPrompt = LocalSqlSupport.buildSqlCoderPrompt(schema, question, validation.formatMessage());
        String retryReply = localAi.chatWithSqlModel(system, retryPrompt);
        String retryCandidate = LocalSqlSupport.normalizeSqlReply(retryReply);
        LocalSqlSupport.ValidationResult retryValidation = LocalSqlSupport.validateSqlAgainstSchema(retryCandidate, schema);
        if (retryValidation.isValid()) {
            return retryCandidate;
        }

        if (!candidate.isBlank()) {
            return candidate;
        }
        return retryCandidate;
    }

    private String validatePlan(AiService.AnalysisPlan plan, SchemaService.SchemaInfo schema) {
        if (plan == null || plan.queries == null || plan.queries.isEmpty()) {
            return "planner returned no queries";
        }
        if (plan.queries.size() > 3) {
            return "planner returned more than 3 queries";
        }
        java.util.List<String> issues = new java.util.ArrayList<>();
        for (AiService.AnalysisQuery query : plan.queries) {
            if (query == null || query.sql == null || query.sql.isBlank()) {
                issues.add("query is empty");
                continue;
            }
            LocalSqlSupport.ValidationResult validation = LocalSqlSupport.validateSqlAgainstSchema(query.sql, schema);
            if (!validation.isValid()) {
                issues.add(safeLabel(query.purpose, query.id) + ": " + validation.formatMessage());
            }
        }
        return issues.isEmpty() ? null : String.join("; ", issues);
    }

    private String buildValidationMessage(LocalSqlSupport.ValidationResult validation) {
        String base = "I couldn't build a schema-safe private analysis for that question yet.";
        if (validation == null) {
            return base + " Try asking about a clearer metric or a narrower time period.";
        }
        return base + " Try asking about a clearer metric, entity, or time period.\n\nDetail: " + validation.formatMessage();
    }

    private boolean needsSupportingCoverage(String question, AiService.AnalysisPlan plan) {
        if (plan == null || plan.queries == null || plan.queries.isEmpty()) {
            return false;
        }

        String q = question == null ? "" : question.toLowerCase(java.util.Locale.ROOT);
        boolean financialQuestion = q.contains("profit")
                || q.contains("revenue")
                || q.contains("sales")
                || q.contains("income")
                || q.contains("прибут")
                || q.contains("дохід")
                || q.contains("вируч")
                || q.contains("продаж");
        if (!financialQuestion) {
            return false;
        }

        boolean hasAggregate = false;
        boolean hasCoverage = false;
        for (AiService.AnalysisQuery query : plan.queries) {
            if (query == null || query.sql == null) {
                continue;
            }
            String sql = query.sql.toUpperCase(java.util.Locale.ROOT);
            if (sql.contains("SUM(") || sql.contains("AVG(") || sql.contains("TOTAL(")) {
                hasAggregate = true;
            }
            if (sql.contains("COUNT(")) {
                hasCoverage = true;
            }

            String purpose = query.purpose == null ? "" : query.purpose.toLowerCase(java.util.Locale.ROOT);
            if (purpose.contains("count") || purpose.contains("coverage") || purpose.contains("customer")) {
                hasCoverage = true;
            }
        }
        return hasAggregate && !hasCoverage;
    }

    private void statusOnUi(Label status, String text) {
        javafx.application.Platform.runLater(() -> status.setText(text));
    }

    private String safeLabel(String purpose, String id) {
        if (purpose != null && !purpose.isBlank()) {
            return purpose;
        }
        return id != null && !id.isBlank() ? id : "query";
    }

    private void appendChat(TextArea history, String role, String content) {
        String prefix = switch (role) {
            case "user" -> "You: ";
            case "assistant" -> "Local analyst: ";
            default -> "";
        };
        String current = history.getText();
        if (current == null) current = "";
        if (!current.isBlank()) current += "\n\n";
        history.setText(current + prefix + content);
        history.positionCaret(history.getText().length());
    }
}
