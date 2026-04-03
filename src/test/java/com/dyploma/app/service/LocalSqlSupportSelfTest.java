package com.dyploma.app.service;

public class LocalSqlSupportSelfTest {

    public static void main(String[] args) {
        shouldBuildSqlCoderPrompt();
        shouldRejectInventedSalesColumn();
        shouldAcceptSelectAliasInOrderBy();
        shouldAcceptAliasedQuery();
        shouldIgnoreStringLiteralsDuringValidation();
        shouldDefaultAnalysisModelToGemma();
        shouldDefaultSqlModelToSqlCoder();
        shouldUseSeparateOllamaEndpoints();
        System.out.println("LocalSqlSupportSelfTest: OK");
    }

    private static void shouldBuildSqlCoderPrompt() {
        SchemaService.SchemaInfo schema = sampleSchema();
        String prompt = LocalSqlSupport.buildSqlCoderPrompt(schema, "Get the top 5 best-selling tracks", null);

        assertTrue(prompt.contains("### Instructions:"), "Prompt should contain SQLCoder instructions header");
        assertTrue(prompt.contains("Get the top 5 best-selling tracks"), "Prompt should contain the question");
        assertTrue(prompt.contains("CREATE TABLE tracks"), "Prompt should contain schema DDL");
        assertTrue(prompt.contains("Do not invent a Sales column"), "Prompt should include schema-specific best-selling hint");
    }

    private static void shouldRejectInventedSalesColumn() {
        SchemaService.SchemaInfo schema = sampleSchema();
        var validation = LocalSqlSupport.validateSqlAgainstSchema(
                "SELECT t.TrackId, t.Name FROM tracks t ORDER BY Sales DESC LIMIT 5",
                schema
        );

        assertFalse(validation.isValid(), "Validation should reject invented Sales column");
        assertTrue(validation.getUnknownIdentifiers().contains("Sales"), "Validation should mention Sales");
    }

    private static void shouldAcceptSelectAliasInOrderBy() {
        SchemaService.SchemaInfo schema = sampleSchema();
        var validation = LocalSqlSupport.validateSqlAgainstSchema(
                "SELECT t.Name, SUM(i.Quantity) AS Sales FROM tracks t JOIN invoice_items i ON i.TrackId = t.TrackId GROUP BY t.Name ORDER BY Sales DESC LIMIT 5",
                schema
        );

        assertTrue(validation.isValid(), "Validation should accept ORDER BY on a SELECT alias");
    }

    private static void shouldAcceptAliasedQuery() {
        SchemaService.SchemaInfo schema = sampleSchema();
        var validation = LocalSqlSupport.validateSqlAgainstSchema(
                "SELECT t.TrackId, t.Name FROM tracks t JOIN invoice_items ii ON ii.TrackId = t.TrackId ORDER BY t.Name LIMIT 5",
                schema
        );

        assertTrue(validation.isValid(), "Validation should accept a query that uses known aliases and columns");
    }

    private static void shouldIgnoreStringLiteralsDuringValidation() {
        SchemaService.SchemaInfo schema = sampleCustomerSchema();
        var validation = LocalSqlSupport.validateSqlAgainstSchema(
                "SELECT * FROM customers WHERE Country = 'USA'",
                schema
        );

        assertTrue(validation.isValid(), "Validation should ignore string literals like 'USA'");
    }

    private static void shouldDefaultAnalysisModelToGemma() {
        LocalAnalysisService service = new LocalAnalysisService();
        assertEquals("gemma3:1b", service.getConfiguredAnalysisModel(), "Default analysis model should be gemma3:1b");
    }

    private static void shouldDefaultSqlModelToSqlCoder() {
        LocalAnalysisService service = new LocalAnalysisService();
        assertEquals("sqlcoder", service.getConfiguredSqlModel(), "Default SQL model should be sqlcoder");
    }

    private static void shouldUseSeparateOllamaEndpoints() {
        LocalAnalysisService service = new LocalAnalysisService();
        assertEquals("http://localhost:11434/api/generate", service.getConfiguredSqlUrl(), "SQL model should use Ollama generate endpoint");
        assertEquals("http://localhost:11434/api/chat", service.getConfiguredAnalysisUrl(), "Analysis model should use Ollama chat endpoint");
    }

    private static SchemaService.SchemaInfo sampleSchema() {
        SchemaService.SchemaInfo schema = new SchemaService.SchemaInfo();
        schema.dialect = "sqlite";

        SchemaService.TableInfo tracks = new SchemaService.TableInfo();
        tracks.name = "tracks";
        tracks.columns.add(column("TrackId", "INTEGER"));
        tracks.columns.add(column("Name", "TEXT"));

        SchemaService.TableInfo invoiceItems = new SchemaService.TableInfo();
        invoiceItems.name = "invoice_items";
        invoiceItems.columns.add(column("TrackId", "INTEGER"));
        invoiceItems.columns.add(column("Quantity", "INTEGER"));
        invoiceItems.columns.add(column("UnitPrice", "DECIMAL"));

        schema.tables.add(tracks);
        schema.tables.add(invoiceItems);
        return schema;
    }

    private static SchemaService.SchemaInfo sampleCustomerSchema() {
        SchemaService.SchemaInfo schema = new SchemaService.SchemaInfo();
        schema.dialect = "sqlite";

        SchemaService.TableInfo customers = new SchemaService.TableInfo();
        customers.name = "customers";
        customers.columns.add(column("CustomerId", "INTEGER"));
        customers.columns.add(column("FirstName", "TEXT"));
        customers.columns.add(column("Country", "TEXT"));

        schema.tables.add(customers);
        return schema;
    }

    private static SchemaService.ColumnInfo column(String name, String type) {
        SchemaService.ColumnInfo column = new SchemaService.ColumnInfo();
        column.name = name;
        column.type = type;
        return column;
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ". Expected: " + expected + ", actual: " + actual);
        }
    }
}
