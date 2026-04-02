package com.dyploma.app.ui;

import com.dyploma.app.dao.ConnectionDao;
import com.dyploma.app.service.LocalAnalysisService;
import com.dyploma.app.service.SchemaService;
import com.dyploma.app.service.SqlExecuteService;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.control.TableView;
import javafx.scene.control.TableColumn;

import java.util.List;

/**
 * Екран для локальної моделі (Ollama): питання -> SQL (з урахуванням схеми) -> локальне виконання -> таблиця результату.
 */
public class LocalSqlGenView {

    private final SceneManager sceneManager;

    public LocalSqlGenView(SceneManager sceneManager) {
        this.sceneManager = sceneManager;
    }

    public VBox build() {
        Label title = new Label("Local SQL (via Ollama)");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        // Підказка щодо активної схеми
        SchemaService.SchemaInfo schema = AppState.getCurrentSchema();
        Label schemaHint = new Label(schema != null ? "Schema: loaded" : "Schema: not loaded (go to Dashboard → Refresh schema)");

        // Поле питання та згенерований SQL
        TextArea question = new TextArea();
        question.setPromptText("Ask a question about your data (local model will generate SQL based on the current schema)...");
        question.setPrefRowCount(4);

        TextArea sqlArea = new TextArea();
        sqlArea.setPromptText("Generated SQL will appear here...");
        sqlArea.setEditable(false);
        sqlArea.setPrefRowCount(5);

        Button genBtn = new Button("Generate SQL");
        Button runBtn = new Button("Run locally");
        runBtn.setDisable(true);
        Button backBtn = new Button("Back to Dashboard");

        Label status = new Label("Ready");

        // Таблиця результатів
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
                status.setText("Schema is not loaded — go to Dashboard and click 'Refresh schema'");
                return;
            }
            // Побудуємо DDL-подібне представлення схеми, як у прикладі duckdb-nsql
            StringBuilder ddl = new StringBuilder();
            ddl.append("Provided this schema:\n\n");
            for (var t : sc.tables) {
                ddl.append("CREATE TABLE ").append(t.name).append(" (\n");
                for (int j = 0; j < t.columns.size(); j++) {
                    var c = t.columns.get(j);
                    ddl.append("    ").append(c.name).append(" ").append(mapType(c.type));
                    if (j + 1 < t.columns.size()) ddl.append(",");
                    ddl.append("\n");
                }
                ddl.append(");\n\n");
            }
            String ddlText = ddl.toString();
            try { System.out.println("[DEBUG_LOG] local schema DDL length: " + ddlText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length); } catch (Exception ignore) {}

            genBtn.setDisable(true);
            runBtn.setDisable(true);
            status.setText("Generating SQL via local model…");

            new Thread(() -> {
                try {
                    String system = "You generate strictly valid SQL using ONLY table/column names from the provided DDL. Return ONE statement, no semicolon. Dialect: " + sc.dialect + ". " +
                            "For negative existence use NOT EXISTS or LEFT JOIN ... IS NULL (not NOT IN). For top-N use ORDER BY + LIMIT N. " +
                            "If the user asks for best-selling/best sellers/top by sales, infer sales from a line-items table (e.g., invoice_items/invoice_lines) joined to tracks by TrackId, and rank by SUM(quantity) or SUM(unit_price*quantity). Do NOT invent column names like Sales; use only names from the DDL.";
                    String userMsg = ddlText + "Question: " + q + "\nReturn ONE valid SELECT (or WITH ... SELECT). Do not include semicolons.";
                    String reply = new LocalAnalysisService().chat(system, userMsg);
                    // Невеликий прев'ю в лог
                    try {
                        String prev = reply == null ? "" : reply.replaceAll("\n", " ");
                        if (prev.length() > 200) prev = prev.substring(0, 200) + "...";
                        System.out.println("[DEBUG_LOG] local toSql (first): " + prev);
                    } catch (Exception ignore) {}

                    // Перевіримо SQL проти схеми; якщо є невідомі імена — один ретрай з підказкою
                    String candidate = reply == null ? "" : reply.trim();
                    String issues = findUnknownIdentifiers(candidate, sc);
                    if (issues != null && !issues.isBlank()) {
                        System.out.println("[DEBUG_LOG] local toSql validation issues: " + issues);
                        String retrySystem = system;
                        String retryUser = ddlText + "Question: " + q + "\nYour previous SQL referenced unknown identifiers: " + issues +
                                ". Use ONLY names from the DDL. For best-selling, aggregate from the line-items table by TrackId and ORDER BY SUM(quantity) or SUM(unit_price*quantity). Return ONE valid SELECT without semicolon.";
                        String retry = new LocalAnalysisService().chat(retrySystem, retryUser);
                        try {
                            String prev2 = retry == null ? "" : retry.replaceAll("\n", " ");
                            if (prev2.length() > 200) prev2 = prev2.substring(0, 200) + "...";
                            System.out.println("[DEBUG_LOG] local toSql (retry): " + prev2);
                        } catch (Exception ignore) {}
                        String retryIssues = findUnknownIdentifiers(retry, sc);
                        if (retryIssues == null || retryIssues.isBlank()) {
                            candidate = retry == null ? "" : retry.trim();
                        } // інакше залишимо перший варіант — користувач побачить помилку під час run
                    }

                    final String finalSql = candidate;
                    javafx.application.Platform.runLater(() -> {
                        sqlArea.setText(finalSql);
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

            runBtn.setDisable(true);
            status.setText("Running locally…");

            new Thread(() -> {
                try {
                    String clean = sql.trim();
                    if (clean.endsWith(";")) clean = clean.substring(0, clean.length()-1).trim();
                    SqlExecuteService exec = new SqlExecuteService();
                    // Лічи кількість рядків і витягни семпл для таблиці
                    long count = 0L;
                    try { count = exec.countRows(conn, clean, 15); } catch (Exception ignore) {}
                    var sample = exec.querySample(conn, clean, 1000, 20);

                    final long fCount = count;
                    final List<String> cols = sample.columns;
                    final List<List<Object>> rows = sample.rows;
                    javafx.application.Platform.runLater(() -> {
                        renderTable(table, cols, rows);
                        status.setText("Done (rows_shown=" + (rows == null ? 0 : rows.size()) + ", total~=" + fCount + ")");
                        runBtn.setDisable(false);
                    });
                } catch (Exception ex2) {
                    javafx.application.Platform.runLater(() -> {
                        status.setText("Error: " + ex2.getMessage());
                        runBtn.setDisable(false);
                    });
                }
            }, "local-sql-run").start();
        });

        backBtn.setOnAction(e -> sceneManager.switchTo(new com.dyploma.app.ui.dashboard.DashboardView(sceneManager).build(), "Dashboard"));

        HBox btns = new HBox(10, genBtn, runBtn, backBtn);
        VBox root = new VBox(12, title, schemaHint, new Label("Question"), question, new Label("Generated SQL"), sqlArea, btns, table, status);
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

    // Пошук невідомих ідентифікаторів (таблиці/стовпці), яких немає у SchemaInfo
    private String findUnknownIdentifiers(String sql, SchemaService.SchemaInfo sc) {
        if (sql == null || sql.isBlank() || sc == null) return null;
        String s = sql.replaceAll("`|\"", "");
        java.util.Set<String> tableSet = new java.util.HashSet<>();
        java.util.Map<String, java.util.Set<String>> colsByTable = new java.util.HashMap<>();
        for (var t : sc.tables) {
            tableSet.add(t.name.toLowerCase());
            var set = new java.util.HashSet<String>();
            for (var c : t.columns) set.add(c.name.toLowerCase());
            colsByTable.put(t.name.toLowerCase(), set);
        }
        java.util.Set<String> unknown = new java.util.HashSet<>();
        // Дуже проста евристика: збираємо слова, що виглядають як ідентифікатори
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(s);
        java.util.List<String> tokens = new java.util.ArrayList<>();
        while (m.find()) tokens.add(m.group());

        // Видалимо SQL-ключові слова, агрегати і службові
        java.util.Set<String> skip = java.util.Set.of("select","with","from","join","left","right","inner","outer","where","group","by","order","limit","on","as","and","or","not","exists","in","is","null","count","sum","avg","min","max","distinct","over","partition","having","case","when","then","end","asc","desc");
        java.util.Set<String> seenTablesOrAliases = new java.util.HashSet<>();
        // Спрощено: після FROM/JOIN наступні токени до ключових слів — потенційні таблиці/аліаси
        for (int i = 0; i < tokens.size(); i++) {
            String tok = tokens.get(i).toLowerCase();
            if (skip.contains(tok)) continue;
            // Вважати таблицями все, що з'являється після FROM/JOIN та не ключове
            if (i>0) {
                String prev = tokens.get(i-1).toLowerCase();
                if (prev.equals("from") || prev.equals("join")) {
                    // Можливий формат schema.table або table alias
                    String base = tok.contains(".") ? tok.substring(tok.indexOf('.')+1) : tok;
                    seenTablesOrAliases.add(base);
                    if (!tableSet.contains(base)) unknown.add(base);
                }
            }
        }
        // Перевірка ORDER BY / SELECT списків на вигадані колонки (важко без AST, зробимо грубий чек)
        for (String tok : tokens) {
            String low = tok.toLowerCase();
            if (skip.contains(low)) continue;
            if (tableSet.contains(low)) continue; // це таблиця
            // якщо токен не є таблицею і не цифрою — може бути колонкою; шукаємо у всіх таблицях
            if (low.matches("[a-z_][a-z0-9_]*") && !low.matches("[0-9]+")) {
                boolean knownCol = false;
                for (var set : colsByTable.values()) { if (set.contains(low)) { knownCol = true; break; } }
                if (!knownCol) {
                    // ігноруємо поширені псевдоніми SUM, COUNT уже відфільтровані; все інше — підозріле
                    // дозволимо trackid/artistid як поширені
                    if (!low.equals("trackid") && !low.equals("artistid")) unknown.add(low);
                }
            }
        }
        if (unknown.isEmpty()) return null;
        return String.join(", ", unknown);
    }

    // Мапінг типів із метаданих у спрощені SQL-типи для DDL-представлення (зрозуміло для моделі)
    private String mapType(String src) {
        if (src == null) return "text";
        String t = src.toLowerCase();
        if (t.contains("int")) return "integer";
        if (t.contains("char") || t.contains("text") || t.contains("clob") || t.contains("string") || t.contains("varchar")) return "text";
        if (t.contains("date") || t.contains("time")) return "timestamp";
        if (t.contains("real") || t.contains("double") || t.contains("float") || t.contains("decimal") || t.contains("numeric")) return "double";
        if (t.contains("bool")) return "boolean";
        if (t.contains("blob")) return "blob";
        // Значення за замовчуванням
        return "text";
    }
}
