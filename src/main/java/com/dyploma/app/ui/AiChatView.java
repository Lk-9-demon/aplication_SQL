package com.dyploma.app.ui;

import com.dyploma.app.dao.ConnectionDao;
import com.dyploma.app.model.User;
import com.dyploma.app.service.AiService;
import com.dyploma.app.service.SchemaService;
import com.dyploma.app.service.SqlExecuteService;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.control.TableView;
import javafx.scene.control.TableColumn;
import javafx.collections.FXCollections;
import javafx.beans.property.SimpleObjectProperty;

import java.util.ArrayList;
import java.util.List;

public class AiChatView {

    private final SceneManager sceneManager;

    public AiChatView(SceneManager sceneManager) {
        this.sceneManager = sceneManager;
    }

    public VBox build() {
        Label title = new Label("AI Assistant");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        SchemaService.SchemaInfo schema = AppState.getCurrentSchema();
        Label schemaHint = new Label(schema != null ? "Schema: loaded" : "Schema: not loaded (go to Dashboard → Refresh schema)");

        // Історія чату
        TextArea chatHistory = new TextArea();
        chatHistory.setEditable(false);
        chatHistory.setPrefRowCount(16);

        TextArea question = new TextArea();
        question.setPromptText("Ask a question about your data in plain English...");
        question.setPrefRowCount(4);

        Button askBtn = new Button("Ask");
        Button backBtn = new Button("Back to Dashboard");
        // Активуємо кнопку, якщо схема вже завантажена; інакше підказка
        askBtn.setDisable(schema == null);

        Label status = new Label("Ready");

        // Панель табличного результату (відображається для запитів, які повертають таблицю)
        TableView<java.util.List<Object>> table = new TableView<>();
        table.setPlaceholder(new Label("No tabular result yet"));
        table.setMaxHeight(320);

        askBtn.setOnAction(e -> {
            String q = question.getText();
            if (q == null || q.isBlank()) {
                status.setText("Enter a question first");
                return;
            }
            SchemaService.SchemaInfo sc = AppState.getCurrentSchema();
            if (sc == null) {
                status.setText("Schema is not loaded — go to Dashboard and click 'Refresh schema'");
                return;
            }

            // знайдемо активне підключення для виконання SQL локально
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
            status.setText("Thinking… (SQL generation)");
            askBtn.setDisable(true);

            new Thread(() -> {
                try {
                    AiService ai = new AiService();
                    String sql = ai.toSql(q, sc); // не показуємо користувачу
                    // Тимчасовий діагностичний лог: показуємо початок згенерованого SQL у консолі
                    try {
                        String preview = sql == null ? "" : sql.replaceAll("\n", " ");
                        if (preview.length() > 200) preview = preview.substring(0, 200) + "...";
                        System.out.println("[DEBUG_LOG] toSql (first): " + preview);
                    } catch (Exception ignore) {}

                    // Локальна перевірка/оцінка результату: рахуємо лише агрегати (без витоку рядків)
                    SqlExecuteService exec = new SqlExecuteService();
                    long rowCount = -1L; // -1 означає "немає метрики"
                    boolean countOk = false;
                    Exception firstErr = null;
                    try {
                        // санітизуємо фінальну ";" на випадок, якщо модель її додала (SqlExecuteService також чистить у count, але продублюємо)
                        String sqlClean = sql != null ? sql.trim() : "";
                        if (sqlClean.endsWith(";")) sqlClean = sqlClean.substring(0, sqlClean.length()-1).trim();
                        rowCount = exec.countRows(conn, sqlClean, 15);
                        countOk = true;
                        System.out.println("[DEBUG_LOG] countRows OK (first try): " + rowCount);
                    } catch (Exception exCnt) {
                        firstErr = exCnt;
                        System.out.println("[DEBUG_LOG] countRows FAILED (first try): " + exCnt.getMessage());
                        // Спробуємо один раз перегенерувати SQL із підказкою про помилку і суворою відповідністю іменам
                        try {
                            String retrySql = ai.toSqlRetryNamesOnly(q, sc, exCnt.getMessage());
                            try {
                                String retryPreview = retrySql == null ? "" : retrySql.replaceAll("\n", " ");
                                if (retryPreview.length() > 200) retryPreview = retryPreview.substring(0, 200) + "...";
                                System.out.println("[DEBUG_LOG] toSql (retry): " + retryPreview);
                            } catch (Exception ignore) {}
                            String retryClean = retrySql != null ? retrySql.trim() : "";
                            if (retryClean.endsWith(";")) retryClean = retryClean.substring(0, retryClean.length()-1).trim();
                            rowCount = exec.countRows(conn, retryClean, 15);
                            sql = retryClean; // зафіксуємо оновлений запит як поточний
                            countOk = true;
                            System.out.println("[DEBUG_LOG] countRows OK (retry): " + rowCount);
                        } catch (Exception exRetry) {
                            System.out.println("[DEBUG_LOG] countRows FAILED (retry): " + exRetry.getMessage());
                            // лишаємо countOk=false, продовжуємо без метрики
                        }
                    }
                    // Якщо запит не є суто агрегатним COUNT, спробуємо отримати обмежений табличний результат
                    // Визначимо ліміт: якщо в питанні є "top N" — беремо N, інакше дефолт 1000
                    int sampleLimit = detectTopN(q);
                    if (sampleLimit <= 0) sampleLimit = 1000;

                    SqlExecuteService.QueryResult sample = null;
                    try {
                        sample = new SqlExecuteService().querySample(conn, sql, sampleLimit, 15);
                    } catch (Exception exSample) {
                        // Якщо sample не вдалось (наприклад, агрегатний COUNT або помилка) — не критично, продовжуємо без таблиці
                        System.out.println("[DEBUG_LOG] querySample FAILED: " + exSample.getMessage());
                    }

                    // Побудуємо метрики для пояснення: колонки + кількість показаних рядків + total row_count (якщо вдалося)
                    StringBuilder mtx = new StringBuilder();
                    if (countOk) {
                        mtx.append("row_count=").append(rowCount);
                    }
                    if (sample != null && sample.columns != null && !sample.columns.isEmpty()) {
                        if (mtx.length() > 0) mtx.append("; ");
                        mtx.append("columns=").append(String.join(",", sample.columns));
                        if (mtx.length() > 0) mtx.append("; ");
                        mtx.append("rows_shown=").append(sample.rows != null ? sample.rows.size() : 0);
                    }
                    String metrics = mtx.length() == 0 ? null : mtx.toString();

                    // Етап 2: пояснення для користувача на основі СХЕМИ + агрегованих метрик (без сирих даних)
                    String explanation = ai.toExplanationWithMetrics(q, sc, sql, metrics);

                    // Підготуємо фінальні посилання для лямбди
                    final SqlExecuteService.QueryResult fSample = sample;
                    final java.util.List<String> fCols = (fSample != null) ? fSample.columns : null;
                    final java.util.List<java.util.List<Object>> fRows = (fSample != null) ? fSample.rows : null;
                    final String fExplanation = explanation;

                    javafx.application.Platform.runLater(() -> {
                        appendChat(chatHistory, "assistant", fExplanation);
                        // Якщо маємо табличний результат — покажемо під історією чату
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
        VBox root = new VBox(12, title, schemaHint, chatHistory, table, question, buttons, status);
        root.setPadding(new Insets(16));
        root.setMaxWidth(900);

        // Додаткова перевірка активності кнопки після відображення екрана (на випадок, якщо схема вже була завантажена)
        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                javafx.application.Platform.runLater(() -> {
                    SchemaService.SchemaInfo scNow = AppState.getCurrentSchema();
                    askBtn.setDisable(scNow == null);
                });
            }
        });
        return root;
    }

    private void renderTable(TableView<java.util.List<Object>> table,
                              java.util.List<String> columns,
                              java.util.List<java.util.List<Object>> rows) {
        table.getColumns().clear();
        table.getItems().clear();
        if (columns == null || columns.isEmpty()) return;
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

    // Проста детекція top N з тексту питання
    private int detectTopN(String question) {
        if (question == null) return -1;
        String q = question.toLowerCase();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("top[- ]?(\\d+)").matcher(q);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignore) {}
        }
        m = java.util.regex.Pattern.compile("first[ ]+(\\d+)").matcher(q);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignore) {}
        }
        m = java.util.regex.Pattern.compile("найкращ[іi][ ]+(\\d+)|лучшие[ ]+(\\d+)").matcher(q);
        if (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                String g = m.group(i);
                if (g != null) {
                    try { return Integer.parseInt(g); } catch (Exception ignore) {}
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
        if (current == null) current = "";
        if (!current.isBlank()) current += "\n\n";
        history.setText(current + prefix + content);
        history.positionCaret(history.getText().length());
    }
}
