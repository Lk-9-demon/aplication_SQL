package com.dyploma.app.ui;

import com.dyploma.app.model.User;
import com.dyploma.app.service.SchemaService;

public class AppState {
    private static User currentUser;
    // Кеш метаданих поточної БД (схеми) для AI
    private static SchemaService.SchemaInfo currentSchema;

    public static User getCurrentUser() { return currentUser; }
    public static void setCurrentUser(User user) { currentUser = user; }

    public static SchemaService.SchemaInfo getCurrentSchema() { return currentSchema; }
    public static void setCurrentSchema(SchemaService.SchemaInfo schema) { currentSchema = schema; }
}
