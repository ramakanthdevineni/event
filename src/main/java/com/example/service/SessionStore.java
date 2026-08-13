package com.example.service;

public interface SessionStore {
    record Session(String sessionId, String username, long lastActivityMs) {}

    void create(String sessionId, String username, long lastActivityMs);

    Session find(String sessionId);

    void touch(String sessionId, long lastActivityMs);

    void delete(String sessionId);

    void deleteByUsername(String username);

    void pruneOldest(String username, int keep);

    /** JDBC-backed stores expire rows; Redis uses key TTL. */
    int purgeExpired(long cutoffMs);
}
