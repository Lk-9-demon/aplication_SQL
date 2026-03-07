package com.dyploma.app.service;

import com.dyploma.app.dao.UserDao;
import com.dyploma.app.model.User;
import org.mindrot.jbcrypt.BCrypt;

public class AuthService {
    private final UserDao userDao = new UserDao();

    public User register(String username, String password, String confirm) {
        username = username == null ? "" : username.trim();

        if (username.isBlank()) throw new IllegalArgumentException("Username is required");
        if (password == null || password.length() < 6) throw new IllegalArgumentException("Password min 6 chars");
        if (!password.equals(confirm)) throw new IllegalArgumentException("Passwords do not match");

        if (userDao.findByUsername(username) != null) {
            throw new IllegalArgumentException("Username already exists");
        }

        String hash = BCrypt.hashpw(password, BCrypt.gensalt(12));
        return userDao.createUser(username, hash);
    }

    public User login(String username, String password) {
        username = username == null ? "" : username.trim();
        if (username.isBlank()) throw new IllegalArgumentException("Username is required");
        if (password == null) password = "";

        String hash = userDao.getPasswordHash(username);
        if (hash == null) throw new IllegalArgumentException("User not found");

        if (!BCrypt.checkpw(password, hash)) {
            throw new IllegalArgumentException("Wrong password");
        }

        return userDao.findByUsername(username);
    }
}
