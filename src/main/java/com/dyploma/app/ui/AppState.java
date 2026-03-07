package com.dyploma.app.ui;

import com.dyploma.app.model.User;

public class AppState {
    private static User currentUser;

    public static User getCurrentUser() { return currentUser; }
    public static void setCurrentUser(User user) { currentUser = user; }
}
