package com.example.service;

import com.example.App;
import com.example.config.AppProperties;
import com.example.security.PasswordService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class DatabaseBootstrap {
    private static final Logger log = LoggerFactory.getLogger(DatabaseBootstrap.class);

    private final AppProperties props;
    private final PasswordService passwords;
    private final JdbcTemplate users;
    private final JdbcTemplate status;

    public DatabaseBootstrap(AppProperties props,
                             PasswordService passwords,
                             @Qualifier("usersJdbc") JdbcTemplate users,
                             @Qualifier("statusJdbc") JdbcTemplate status) {
        this.props = props;
        this.passwords = passwords;
        this.users = users;
        this.status = status;
    }

    @PostConstruct
    public void init() throws Exception {
        if (props.isMysql()) {
            waitForMysql();
            createMysqlSchema();
            if (props.serves("core")) {
                migrateFromSqliteIfNeeded();
                seedDefaults();
                rehashLegacyPasswords();
            }
        } else {
            App.initDatabase();
            rehashLegacyPasswords();
        }
    }

    private void waitForMysql() throws InterruptedException {
        for (int i = 1; i <= 40; i++) {
            try {
                users.queryForObject("SELECT 1", Integer.class);
                return;
            } catch (Exception ex) {
                log.warn("Waiting for MySQL ({}/40): {}", i, ex.getMessage());
                Thread.sleep(1500L);
            }
        }
        throw new IllegalStateException("MySQL is not reachable");
    }

    private void createMysqlSchema() {
        users.execute("""
                CREATE TABLE IF NOT EXISTS users (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  first_name VARCHAR(255) NOT NULL,
                  last_name VARCHAR(255) NOT NULL,
                  username VARCHAR(255) NOT NULL UNIQUE,
                  email VARCHAR(255) NOT NULL UNIQUE,
                  password VARCHAR(255) NOT NULL,
                  is_admin TINYINT NOT NULL DEFAULT 0,
                  must_change_password TINYINT NOT NULL DEFAULT 0,
                  is_enabled TINYINT NOT NULL DEFAULT 1,
                  role VARCHAR(1024) NOT NULL DEFAULT 'user',
                  created_at VARCHAR(64) NOT NULL,
                  last_login_at VARCHAR(64) NULL,
                  previous_login_at VARCHAR(64) NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        users.execute("""
                CREATE TABLE IF NOT EXISTS nav_options (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  label VARCHAR(255) NOT NULL UNIQUE,
                  created_at VARCHAR(64) NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        users.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                  session_id VARCHAR(64) PRIMARY KEY,
                  username VARCHAR(255) NOT NULL,
                  last_activity_ms BIGINT NOT NULL,
                  INDEX idx_sessions_username (username)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        status.execute("""
                CREATE TABLE IF NOT EXISTS work_item_defs (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  name VARCHAR(255) NOT NULL UNIQUE,
                  sort_order INT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        status.execute("""
                CREATE TABLE IF NOT EXISTS status_defs (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  label VARCHAR(255) NOT NULL UNIQUE,
                  percent_value INT NOT NULL DEFAULT 0,
                  sort_order INT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        status.execute("""
                CREATE TABLE IF NOT EXISTS nav_work_items (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  nav_option_id BIGINT NOT NULL,
                  option_label VARCHAR(255) NOT NULL,
                  item_name VARCHAR(255) NOT NULL,
                  status VARCHAR(255) NOT NULL DEFAULT 'Not started',
                  updated_at VARCHAR(64) NOT NULL,
                  UNIQUE KEY uk_nav_item (nav_option_id, item_name),
                  INDEX idx_nav_work_items_option (nav_option_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        status.execute("""
                CREATE TABLE IF NOT EXISTS activity_logs (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  changed_at VARCHAR(64) NOT NULL,
                  changed_at_ms BIGINT NOT NULL,
                  event_type VARCHAR(64) NOT NULL,
                  username VARCHAR(255) NOT NULL,
                  user_display_name VARCHAR(255) NOT NULL,
                  target VARCHAR(512) NOT NULL DEFAULT '',
                  details VARCHAR(1024) NOT NULL DEFAULT '',
                  venue_label VARCHAR(255) NOT NULL DEFAULT '',
                  item_name VARCHAR(255) NOT NULL DEFAULT '',
                  old_value VARCHAR(512) NOT NULL DEFAULT '',
                  new_value VARCHAR(512) NOT NULL DEFAULT '',
                  INDEX idx_activity_logs_time (changed_at_ms),
                  INDEX idx_activity_logs_user (username),
                  INDEX idx_activity_logs_type (event_type)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        migrateStatusChangeLogsToActivityLogs();
        migrateUserLoginColumns();
        users.execute("""
                CREATE TABLE IF NOT EXISTS user_role_history (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  changed_at VARCHAR(64) NOT NULL,
                  changed_at_ms BIGINT NOT NULL,
                  target_username VARCHAR(255) NOT NULL,
                  actor_username VARCHAR(255) NOT NULL,
                  actor_display_name VARCHAR(255) NOT NULL,
                  old_roles VARCHAR(512) NOT NULL DEFAULT '',
                  new_roles VARCHAR(512) NOT NULL DEFAULT '',
                  change_type VARCHAR(32) NOT NULL DEFAULT 'update',
                  INDEX idx_user_role_history_target (target_username),
                  INDEX idx_user_role_history_time (changed_at_ms),
                  INDEX idx_user_role_history_actor (actor_username)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void migrateUserLoginColumns() {
        try {
            users.execute("ALTER TABLE users ADD COLUMN last_login_at VARCHAR(64) NULL");
        } catch (Exception ignored) {
            // column may already exist
        }
        try {
            users.execute("ALTER TABLE users ADD COLUMN previous_login_at VARCHAR(64) NULL");
        } catch (Exception ignored) {
            // column may already exist
        }
    }

    private void migrateStatusChangeLogsToActivityLogs() {
        try {
            Integer activityCount = status.queryForObject("SELECT COUNT(*) FROM activity_logs", Integer.class);
            if (activityCount != null && activityCount > 0) return;
            status.update("""
                    INSERT INTO activity_logs(changed_at, changed_at_ms, event_type, username, user_display_name, target, details, venue_label, item_name, old_value, new_value)
                    SELECT changed_at, changed_at_ms, 'VENUE_STATUS_CHANGE', username, user_display_name,
                           CONCAT(venue_label, ' / ', item_name),
                           CONCAT(old_status, ' → ', new_status),
                           venue_label, item_name, old_status, new_status
                    FROM status_change_logs
                    """);
        } catch (Exception ignored) {
            // status_change_logs may not exist on fresh installs
        }
    }

    private void seedDefaults() {
        Integer userCount = users.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        if (userCount != null && userCount == 0) {
            users.update(
                    "INSERT INTO users(first_name,last_name,username,email,password,is_admin,must_change_password,is_enabled,role,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    "Admin", "User", props.getDefaultAdminUsername(), "admin@example.com",
                    passwords.hash(props.getDefaultAdminPassword()), 1, 0, 1, "admin", Instant.now().toString());
        } else {
            users.update("UPDATE users SET is_admin=1, role='admin' WHERE username=?", props.getDefaultAdminUsername());
        }

        Integer workCount = status.queryForObject("SELECT COUNT(*) FROM work_item_defs", Integer.class);
        if (workCount != null && workCount == 0) {
            String[] defaults = {"Fiber Laying", "PTA", "STA", "LAN Cabling", "Media Center"};
            for (int i = 0; i < defaults.length; i++) {
                status.update("INSERT INTO work_item_defs(name, sort_order) VALUES (?,?)", defaults[i], i + 1);
            }
        }
        Integer statusCount = status.queryForObject("SELECT COUNT(*) FROM status_defs", Integer.class);
        if (statusCount != null && statusCount == 0) {
            status.update("INSERT INTO status_defs(label, percent_value, sort_order) VALUES (?,?,?)", "Not started", 0, 1);
            status.update("INSERT INTO status_defs(label, percent_value, sort_order) VALUES (?,?,?)", "In Progress", 25, 2);
            status.update("INSERT INTO status_defs(label, percent_value, sort_order) VALUES (?,?,?)", "50% Complete", 50, 3);
            status.update("INSERT INTO status_defs(label, percent_value, sort_order) VALUES (?,?,?)", "75% Complete", 75, 4);
            status.update("INSERT INTO status_defs(label, percent_value, sort_order) VALUES (?,?,?)", "Completed", 100, 5);
        }
    }

    private void migrateFromSqliteIfNeeded() {
        Path usersDb = Path.of("data", "users.db");
        Path statusDb = Path.of("data", "status.db");
        Integer count = users.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        if (!Files.exists(usersDb)) {
            return;
        }
        log.info("Migrating existing SQLite data into MySQL…");
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection sqlite = DriverManager.getConnection("jdbc:sqlite:" + usersDb.toAbsolutePath())) {
                copyUsers(sqlite);
                copyNavOptions(sqlite);
                copySessions(sqlite);
            }
            if (Files.exists(statusDb)) {
                try (Connection sqlite = DriverManager.getConnection("jdbc:sqlite:" + statusDb.toAbsolutePath())) {
                    copyWorkItemDefs(sqlite);
                    copyStatusDefs(sqlite);
                    copyNavWorkItems(sqlite);
                }
            }
            log.info("SQLite → MySQL migration finished");
        } catch (Exception ex) {
            log.warn("SQLite migration skipped/failed: {}", ex.getMessage());
        }
    }

    private void copyUsers(Connection sqlite) throws Exception {
        try (Statement st = sqlite.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT first_name,last_name,username,email,password,is_admin,must_change_password," +
                             "COALESCE(is_enabled,1) AS is_enabled,COALESCE(role,'user') AS role,created_at FROM users")) {
            while (rs.next()) {
                users.update(
                        "INSERT IGNORE INTO users(first_name,last_name,username,email,password,is_admin,must_change_password,is_enabled,role,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        rs.getString("first_name"), rs.getString("last_name"), rs.getString("username"), rs.getString("email"),
                        rs.getString("password"), rs.getInt("is_admin"), rs.getInt("must_change_password"),
                        rs.getInt("is_enabled"), rs.getString("role"), rs.getString("created_at"));
            }
        }
    }

    private void copyNavOptions(Connection sqlite) throws Exception {
        try (Statement st = sqlite.createStatement();
             ResultSet rs = st.executeQuery("SELECT id,label,created_at FROM nav_options")) {
            while (rs.next()) {
                users.update("INSERT IGNORE INTO nav_options(id,label,created_at) VALUES (?,?,?)",
                        rs.getLong("id"), rs.getString("label"), rs.getString("created_at"));
            }
        }
    }

    private void copySessions(Connection sqlite) throws Exception {
        try (Statement st = sqlite.createStatement();
             ResultSet rs = st.executeQuery("SELECT session_id,username,last_activity_ms FROM sessions")) {
            while (rs.next()) {
                users.update("INSERT IGNORE INTO sessions(session_id,username,last_activity_ms) VALUES (?,?,?)",
                        rs.getString("session_id"), rs.getString("username"), rs.getLong("last_activity_ms"));
            }
        }
    }

    private void copyWorkItemDefs(Connection sqlite) throws Exception {
        try (Statement st = sqlite.createStatement();
             ResultSet rs = st.executeQuery("SELECT id,name,sort_order FROM work_item_defs")) {
            while (rs.next()) {
                status.update("INSERT IGNORE INTO work_item_defs(id,name,sort_order) VALUES (?,?,?)",
                        rs.getLong("id"), rs.getString("name"), rs.getInt("sort_order"));
            }
        }
    }

    private void copyStatusDefs(Connection sqlite) throws Exception {
        try (Statement st = sqlite.createStatement();
             ResultSet rs = st.executeQuery("SELECT id,label,percent_value,sort_order FROM status_defs")) {
            while (rs.next()) {
                status.update("INSERT IGNORE INTO status_defs(id,label,percent_value,sort_order) VALUES (?,?,?,?)",
                        rs.getLong("id"), rs.getString("label"), rs.getInt("percent_value"), rs.getInt("sort_order"));
            }
        }
    }

    private void copyNavWorkItems(Connection sqlite) throws Exception {
        try (Statement st = sqlite.createStatement();
             ResultSet rs = st.executeQuery("SELECT nav_option_id,option_label,item_name,status,updated_at FROM nav_work_items")) {
            while (rs.next()) {
                status.update(
                        "INSERT IGNORE INTO nav_work_items(nav_option_id,option_label,item_name,status,updated_at) VALUES (?,?,?,?,?)",
                        rs.getLong("nav_option_id"), rs.getString("option_label"), rs.getString("item_name"),
                        rs.getString("status"), rs.getString("updated_at"));
            }
        }
    }

    private void rehashLegacyPasswords() {
        List<Map<String, Object>> rows = users.queryForList("SELECT username, password FROM users");
        for (Map<String, Object> row : rows) {
            String username = String.valueOf(row.get("username"));
            String stored = row.get("password") == null ? "" : String.valueOf(row.get("password"));
            if (passwords.isHashed(stored)) {
                continue;
            }
            if (stored.equals(props.getDefaultAdminPassword()) || stored.equals(props.getDefaultNewUserPassword())) {
                users.update("UPDATE users SET password=? WHERE username=?", passwords.hash(stored), username);
            }
        }
    }
}
