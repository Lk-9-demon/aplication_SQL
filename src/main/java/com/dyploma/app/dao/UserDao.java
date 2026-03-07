package com.dyploma.app.dao;

import com.dyploma.app.model.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class UserDao {

    public User createUser(String username, String passwordHash) {
        String sql = "INSERT INTO users(username, password_hash) VALUES(?, ?);";

        try (Connection conn = com.dyploma.app.dao.LocalDb.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    long id = keys.getLong(1);
                    return new User(id, username);
                }
            }
            throw new RuntimeException("Cannot get generated user id");
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public User findByUsername(String username) {
        String sql = "SELECT id, username FROM users WHERE username = ?;";

        try (Connection conn = com.dyploma.app.dao.LocalDb.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, username);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new User(rs.getLong("id"), rs.getString("username"));
                }
                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public String getPasswordHash(String username) {
        String sql = "SELECT password_hash FROM users WHERE username = ?;";

        try (Connection conn = com.dyploma.app.dao.LocalDb.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, username);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("password_hash");
                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
