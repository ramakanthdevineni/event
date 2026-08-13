package com.example.service;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

public class JdbcSessionStore implements SessionStore {
    private final JdbcTemplate users;

    public JdbcSessionStore(JdbcTemplate users) {
        this.users = users;
    }

    @Override
    public void create(String sessionId, String username, long lastActivityMs) {
        users.update("INSERT INTO sessions(session_id, username, last_activity_ms) VALUES (?, ?, ?)",
                sessionId, username, lastActivityMs);
    }

    @Override
    public Session find(String sessionId) {
        List<Map<String, Object>> rows = users.queryForList(
                "SELECT username, last_activity_ms FROM sessions WHERE session_id = ?", sessionId);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        return new Session(sessionId, String.valueOf(row.get("username")),
                ((Number) row.get("last_activity_ms")).longValue());
    }

    @Override
    public void touch(String sessionId, long lastActivityMs) {
        users.update("UPDATE sessions SET last_activity_ms = ? WHERE session_id = ?", lastActivityMs, sessionId);
    }

    @Override
    public void delete(String sessionId) {
        users.update("DELETE FROM sessions WHERE session_id = ?", sessionId);
    }

    @Override
    public void deleteByUsername(String username) {
        users.update("DELETE FROM sessions WHERE username=?", username);
    }

    @Override
    public void pruneOldest(String username, int keep) {
        List<Map<String, Object>> rows = users.queryForList(
                "SELECT session_id FROM sessions WHERE username=? ORDER BY last_activity_ms DESC", username);
        if (rows.size() <= keep) return;
        for (int i = keep; i < rows.size(); i++) {
            delete(String.valueOf(rows.get(i).get("session_id")));
        }
    }

    @Override
    public int purgeExpired(long cutoffMs) {
        return users.update("DELETE FROM sessions WHERE last_activity_ms < ?", cutoffMs);
    }
}
