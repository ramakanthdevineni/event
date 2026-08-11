package com.example;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class App {
    private static final Path INDEX_HTML = Path.of("index.html");
    private static final Path REGISTRATION_HTML = Path.of("registration.html");
    private static final Path DATA_DIR = Path.of("data");
    private static final String DB_URL = "jdbc:sqlite:data/users.db";
    private static final String STATUS_DB_URL = "jdbc:sqlite:data/status.db";
    private static final String SESSION_COOKIE_NAME = "SESSIONID";
    private static final String DEFAULT_NEW_USER_PASSWORD = "Match123$";
    private static final long SESSION_TIMEOUT_MS = 5 * 60 * 1000L;
    private static final ConcurrentMap<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    private static class SessionInfo {
        final String username;
        volatile long lastActivityMs;

        SessionInfo(String username) {
            this.username = username;
            this.lastActivityMs = System.currentTimeMillis();
        }

        void touch() {
            lastActivityMs = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - lastActivityMs > SESSION_TIMEOUT_MS;
        }
    }

    public static void main(String[] args) throws IOException {
        try {
            initDatabase();
        } catch (SQLException ex) {
            throw new IOException("Unable to initialize database", ex);
        }

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/register", App::handleRegister);
        server.createContext("/login", App::handleLogin);
        server.createContext("/dashboard", App::handleDashboard);
        server.createContext("/users", App::handleUsers);
        server.createContext("/add-user", App::handleAddUser);
        server.createContext("/admin-panel", App::handleAdminPanel);
        server.createContext("/status/export", App::handleStatusExport);
        server.createContext("/status", App::handleStatus);
        server.createContext("/page", App::handleCustomPage);
        server.createContext("/change-password", App::handleChangePassword);
        server.createContext("/profile", App::handleProfile);
        server.createContext("/logout", App::handleLogout);
        server.createContext("/", App::handleHome);
        server.setExecutor(null);

        System.out.println("Java application started on http://localhost:" + port);
        server.start();
    }

    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_PASSWORD = "Certified01$";
    private static final String DEFAULT_ADMIN_EMAIL = "admin@example.com";

    private static void initDatabase() throws SQLException {
        try {
            Files.createDirectories(DATA_DIR);
        } catch (IOException ex) {
            throw new SQLException("Unable to create data directory", ex);
        }

        try (Connection connection = DriverManager.getConnection(DB_URL);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS users (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "first_name TEXT NOT NULL, " +
                    "last_name TEXT NOT NULL, " +
                    "username TEXT NOT NULL UNIQUE, " +
                    "email TEXT NOT NULL UNIQUE, " +
                    "password TEXT NOT NULL, " +
                    "is_admin INTEGER NOT NULL DEFAULT 0, " +
                    "must_change_password INTEGER NOT NULL DEFAULT 0, " +
                    "created_at TEXT NOT NULL)"
            );
            ensureUserTableColumns(connection);
            // add role column for clearer role semantics (admin/user)
            addRoleColumnIfMissing(connection);
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS nav_options (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "label TEXT NOT NULL UNIQUE, " +
                    "created_at TEXT NOT NULL)"
            );
        }
        initStatusDatabase();
        ensureDefaultWorkItemsForAllNavOptions();
        createDefaultAdminUser();
        // ensure role column reflects existing is_admin flags
        try (Connection connection = DriverManager.getConnection(DB_URL);
             Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("UPDATE users SET role = 'admin' WHERE is_admin = 1");
        } catch (SQLException ex) {
            // ignore migration failures
        }
    }

    private static void initStatusDatabase() throws SQLException {
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS work_item_defs (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT NOT NULL UNIQUE, " +
                    "sort_order INTEGER NOT NULL DEFAULT 0)"
            );
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS status_defs (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "label TEXT NOT NULL UNIQUE, " +
                    "percent_value INTEGER NOT NULL DEFAULT 0, " +
                    "sort_order INTEGER NOT NULL DEFAULT 0)"
            );
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS nav_work_items (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "nav_option_id INTEGER NOT NULL, " +
                    "option_label TEXT NOT NULL, " +
                    "item_name TEXT NOT NULL, " +
                    "status TEXT NOT NULL DEFAULT 'Not started', " +
                    "updated_at TEXT NOT NULL, " +
                    "UNIQUE(nav_option_id, item_name))"
            );
        }
        migrateWorkItemsFromUsersDbIfNeeded();
        seedDefaultWorkItemAndStatusDefs();
    }

    private static void seedDefaultWorkItemAndStatusDefs() throws SQLException {
        if (listWorkItemDefs().isEmpty()) {
            String[] defaults = {"Fiber Laying", "PTA", "STA", "LAN Cabling", "Media Center"};
            for (int i = 0; i < defaults.length; i++) {
                insertWorkItemDef(defaults[i], i + 1);
            }
        }
        if (listStatusDefs().isEmpty()) {
            insertStatusDef("Not started", 0, 1);
            insertStatusDef("In Progress", 25, 2);
            insertStatusDef("50% Complete", 50, 3);
            insertStatusDef("75% Complete", 75, 4);
            insertStatusDef("Completed", 100, 5);
        }
    }

    private static void migrateWorkItemsFromUsersDbIfNeeded() {
        try (Connection usersDb = DriverManager.getConnection(DB_URL);
             Statement check = usersDb.createStatement();
             var tables = check.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='nav_work_items'")) {
            if (!tables.next()) {
                return;
            }
        } catch (SQLException ex) {
            return;
        }

        try (Connection usersDb = DriverManager.getConnection(DB_URL);
             Connection statusDb = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement select = usersDb.prepareStatement(
                     "SELECT w.nav_option_id, COALESCE(n.label, ''), w.item_name, w.status " +
                             "FROM nav_work_items w LEFT JOIN nav_options n ON n.id = w.nav_option_id");
             PreparedStatement insert = statusDb.prepareStatement(
                     "INSERT OR IGNORE INTO nav_work_items (nav_option_id, option_label, item_name, status, updated_at) VALUES (?, ?, ?, ?, ?)");
             var rs = select.executeQuery()) {
            while (rs.next()) {
                insert.setInt(1, rs.getInt("nav_option_id"));
                insert.setString(2, rs.getString(2));
                insert.setString(3, rs.getString("item_name"));
                insert.setString(4, rs.getString("status"));
                insert.setString(5, Instant.now().toString());
                insert.addBatch();
            }
            insert.executeBatch();
            try (Statement drop = usersDb.createStatement()) {
                drop.executeUpdate("DROP TABLE IF EXISTS nav_work_items");
            }
        } catch (SQLException ignored) {
            // keep going if migration fails
        }
    }

    private static void ensureUserTableColumns(Connection connection) throws SQLException {
        addColumnIfMissing(connection, "users", "is_admin", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "users", "must_change_password", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "users", "is_enabled", "INTEGER NOT NULL DEFAULT 1");
    }

    private static void addRoleColumnIfMissing(Connection connection) throws SQLException {
        addColumnIfMissing(connection, "users", "role", "TEXT NOT NULL DEFAULT 'user'");
    }

    private static void addColumnIfMissing(Connection connection, String tableName, String columnName, String columnDefinition) throws SQLException {
        boolean columnExists = false;
        try (Statement statement = connection.createStatement();
             var rs = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) {
                if (columnName.equals(rs.getString("name"))) {
                    columnExists = true;
                    break;
                }
            }
        }

        if (!columnExists) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
            }
        }
    }

    private static void createDefaultAdminUser() throws SQLException {
       String insertSql = "INSERT OR IGNORE INTO users (first_name, last_name, username, email, password, is_admin, must_change_password, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
       try (Connection connection = DriverManager.getConnection(DB_URL)) {
           try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
               statement.setString(1, "Admin");
               statement.setString(2, "User");
               statement.setString(3, DEFAULT_ADMIN_USERNAME);
               statement.setString(4, DEFAULT_ADMIN_EMAIL);
               statement.setString(5, DEFAULT_ADMIN_PASSWORD);
               statement.setInt(6, 1);
               statement.setInt(7, 0);
               statement.setString(8, Instant.now().toString());
               statement.executeUpdate();
           }

           try (PreparedStatement updateStatement = connection.prepareStatement(
                   "UPDATE users SET is_admin = 1 WHERE username = ?")) {
               updateStatement.setString(1, DEFAULT_ADMIN_USERNAME);
               updateStatement.executeUpdate();
           }
       }
    }

    private static void handleHome(HttpExchange exchange) throws IOException {
        String username = getSessionUsername(exchange);
        if (username != null) {
            handleDashboard(exchange);
            return;
        }

        handlePage(exchange, INDEX_HTML, "index.html");
    }

    private static void handleRegister(HttpExchange exchange) throws IOException {
        String username = getSessionUsername(exchange);
        if (username != null) {
            handleDashboard(exchange);
            return;
        }

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            handleRegistrationPost(exchange);
            return;
        }

        handlePage(exchange, REGISTRATION_HTML, "registration.html");
    }

    private static void handleLogin(HttpExchange exchange) throws IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            handleLoginPost(exchange);
            return;
        }

        handleHome(exchange);
    }

    private static void handleLoginPost(HttpExchange exchange) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> formData = parseFormData(requestBody);

        String username = formData.getOrDefault("username", "").trim();
        String password = formData.getOrDefault("password", "").trim();

        if (username.isEmpty() || password.isEmpty()) {
            sendHtmlResponse(exchange, 400, buildLoginErrorPage("Login failed", "Please provide both username and password."));
            return;
        }

        try {
            if (!userExists(username)) {
                sendHtmlResponse(exchange, 401, buildLoginErrorPage("Invalid User", ""));
                return;
            }

            UserRecord user = findUserByCredentials(username, password);
            if (user == null) {
                sendHtmlResponse(exchange, 401, buildLoginErrorPage("Invalid Password", ""));
            } else if (!user.enabled) {
                sendHtmlResponse(exchange, 403, buildLoginErrorPage("Account Disabled", "This account has been disabled. Please contact an administrator."));
            } else {
                String sessionId = createSession(username);
                exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE_NAME + "=" + sessionId + "; Path=/; HttpOnly");
                if (user.mustChangePassword) {
                    redirect(exchange, "/change-password");
                } else {
                    java.util.List<NavOption> navOptions = listNavOptions();
                    redirect(exchange, resolveHomePath(user, navOptions));
                }
            }
        } catch (SQLException ex) {
            sendHtmlResponse(exchange, 500, buildLoginErrorPage("Login failed", "Unable to verify credentials right now. Please try again later."));
        }
    }

    private static void handleDashboard(HttpExchange exchange) throws IOException {
        String username = getSessionUsername(exchange);
        if (username == null) {
            redirect(exchange, "/");
            return;
        }

        try {
            UserRecord user = findUserByUsername(username);
            if (user == null) {
                redirect(exchange, "/logout");
                return;
            }
            if (user.mustChangePassword) {
                redirect(exchange, "/change-password");
                return;
            }
            java.util.List<NavOption> navOptions = listNavOptions();
            if (!isAdminRole(user.role) && !isStandardUserRole(user.role)) {
                redirect(exchange, resolveHomePath(user, navOptions));
                return;
            }
            sendHtmlResponse(exchange, 200, buildDashboardPage(user.firstName, username, user.role, navOptions));
        } catch (SQLException ex) {
            sendHtmlResponse(exchange, 500, buildErrorPage("Unable to load your dashboard right now."));
        }
    }

    private static void handleLogout(HttpExchange exchange) throws IOException {
        String sessionId = getSessionIdFromCookie(exchange);
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
        exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE_NAME + "=deleted; Path=/; Max-Age=0; HttpOnly");
        redirect(exchange, "/");
    }

    private static void handleProfile(HttpExchange exchange) throws IOException {
        String username = getSessionUsername(exchange);
        if (username == null) {
            redirect(exchange, "/");
            return;
        }

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            handleProfilePost(exchange, username);
            return;
        }

        try {
            Map<String, String> profile = findUserProfile(username);
            if (profile == null) {
                redirect(exchange, "/logout");
                return;
            }
            sendHtmlResponse(exchange, 200, buildProfilePage(username, profile.get("first_name"), profile.get("last_name"), profile.get("email"), null));
        } catch (SQLException ex) {
            sendHtmlResponse(exchange, 500, buildErrorPage("Unable to load your profile right now."));
        }
    }

    private static void handleProfilePost(HttpExchange exchange, String username) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> formData = parseFormData(requestBody);

        String firstName = formData.getOrDefault("firstName", "").trim();
        String lastName = formData.getOrDefault("lastName", "").trim();
        String email = formData.getOrDefault("email", "").trim();
        String password = formData.getOrDefault("password", "").trim();

        if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty()) {
            sendHtmlResponse(exchange, 400, buildProfilePage(username, firstName, lastName, email, "First name, last name, and email are required."));
            return;
        }

        try {
            updateUserProfile(username, firstName, lastName, email, password);
            sendHtmlResponse(exchange, 200, buildProfilePage(username, firstName, lastName, email, "Profile updated successfully."));
        } catch (SQLException ex) {
            String message = ex.getMessage();
            if (message != null && message.contains("UNIQUE")) {
                sendHtmlResponse(exchange, 400, buildProfilePage(username, firstName, lastName, email, "The email address is already in use."));
            } else {
                sendHtmlResponse(exchange, 500, buildProfilePage(username, firstName, lastName, email, "Unable to save profile changes. Please try again later."));
            }
        }
    }

    private static void handleAddUser(HttpExchange exchange) throws IOException {
        String username = getSessionUsername(exchange);
        if (username == null) {
            redirect(exchange, "/");
            return;
        }

        try {
            UserRecord adminUser = findUserByUsername(username);
            if (adminUser == null) {
                redirect(exchange, "/logout");
                return;
            }
            if (!adminUser.isAdmin) {
                redirect(exchange, "/dashboard");
                return;
            }

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleAddUserPost(exchange);
                return;
            }

            sendHtmlResponse(exchange, 200, buildAddUserPage("", "", "", null));
        } catch (SQLException ex) {
            sendHtmlResponse(exchange, 500, buildErrorPage("Unable to load the add user page right now."));
        }
    }

    private static void handleAddUserPost(HttpExchange exchange) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> formData = parseFormData(requestBody);

        String firstName = formData.getOrDefault("firstName", "").trim();
        String lastName = formData.getOrDefault("lastName", "").trim();
        String email = formData.getOrDefault("email", "").trim();
        String username = email;

        if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty()) {
            sendHtmlResponse(exchange, 400, buildAddUserPage(firstName, lastName, email, "First name, last name, and email are required."));
            return;
        }

        try {
            saveUser(firstName, lastName, username, email, DEFAULT_NEW_USER_PASSWORD, false, true);
            sendHtmlResponse(exchange, 200, buildAddUserPage("", "", "", "User created. Email is the username and default password is " + DEFAULT_NEW_USER_PASSWORD + "."));
        } catch (SQLException ex) {
            String message = ex.getMessage();
            if (message != null && message.contains("UNIQUE")) {
                sendHtmlResponse(exchange, 400, buildAddUserPage(firstName, lastName, email, "A user with that email already exists."));
            } else {
                sendHtmlResponse(exchange, 500, buildAddUserPage(firstName, lastName, email, "Unable to create the user. Please try again later."));
            }
        }
    }

    private static void handleAdminPanel(HttpExchange exchange) throws IOException {
        String username = getSessionUsername(exchange);
        if (username == null) {
            redirect(exchange, "/");
            return;
        }

        try {
            UserRecord adminUser = findUserByUsername(username);
            if (adminUser == null) {
                redirect(exchange, "/logout");
                return;
            }
            if (!adminUser.isAdmin) {
                redirect(exchange, "/dashboard");
                return;
            }

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleAdminPanelPost(exchange, username, adminUser.firstName);
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            Map<String, String> q = parseQueryString(query);
            Integer editId = parseNavOptionId(q.get("edit"));
            Integer editWorkItemId = parseNavOptionId(q.get("editWorkItem"));
            Integer editStatusId = parseNavOptionId(q.get("editStatus"));

            sendHtmlResponse(exchange, 200, buildAdminPanelPage(
                    username,
                    adminUser.firstName,
                    "",
                    listNavOptions(),
                    listWorkItemDefs(),
                    listStatusDefs(),
                    null,
                    editId,
                    editWorkItemId,
                    editStatusId
            ));
        } catch (SQLException ex) {
            sendHtmlResponse(exchange, 500, buildErrorPage("Unable to load the admin panel right now."));
        }
    }

    private static void handleAdminPanelPost(HttpExchange exchange, String username, String firstName) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> formData = parseFormData(requestBody);
        String action = formData.getOrDefault("action", "add").trim().toLowerCase();

        try {
            if (action.startsWith("work-item-") || action.startsWith("status-")) {
                handleAdminManagedDefsPost(exchange, username, firstName, action, formData);
                return;
            }
        } catch (SQLException ex) {
            sendHtmlResponse(exchange, 500, buildErrorPage("Unable to update admin settings."));
            return;
        }

        String label = formData.getOrDefault("label", "").trim();
        String idParam = formData.getOrDefault("id", "").trim();

        java.util.List<NavOption> navOptions;
        try {
            navOptions = listNavOptions();
        } catch (SQLException ex) {
            sendHtmlResponse(exchange, 500, buildErrorPage("Unable to load navigation options right now."));
            return;
        }

        if ("delete".equals(action)) {
            handleAdminPanelDelete(exchange, username, firstName, idParam, navOptions);
            return;
        }

        if ("update".equals(action)) {
            handleAdminPanelUpdate(exchange, username, firstName, idParam, label, navOptions);
            return;
        }

        try {
            if (label.isEmpty()) {
                sendAdminPanel(exchange, username, firstName, label, "Please enter a name for the navigation option.", null, null, null, 400);
                return;
            }

            saveNavOption(label);
            sendAdminPanel(exchange, username, firstName, "", "Navigation option \"" + label + "\" added successfully.", null, null, null, 200);
        } catch (SQLException ex) {
            String message = ex.getMessage();
            if (message != null && message.contains("UNIQUE")) {
                sendAdminPanel(exchange, username, firstName, label, "A navigation option with that name already exists.", null, null, null, 400);
            } else {
                sendAdminPanel(exchange, username, firstName, label, "Unable to add the navigation option. Please try again later.", null, null, null, 500);
            }
        }
    }

    private static void handleAdminManagedDefsPost(HttpExchange exchange, String username, String firstName, String action, Map<String, String> form) throws IOException, SQLException {
        String name = form.getOrDefault("name", "").trim();
        String label = form.getOrDefault("label", "").trim();
        String idParam = form.getOrDefault("id", "").trim();
        String percentParam = form.getOrDefault("percent", "").trim();
        Integer id = parseNavOptionId(idParam);

        switch (action) {
            case "work-item-add" -> {
                if (name.isEmpty()) {
                    sendAdminPanel(exchange, username, firstName, "", "Work item name is required.", null, null, null, 400);
                    return;
                }
                try {
                    insertWorkItemDef(name, nextWorkItemSortOrder());
                    seedWorkItemToAllNavOptions(name);
                    redirect(exchange, "/admin-panel");
                } catch (SQLException ex) {
                    sendAdminPanel(exchange, username, firstName, "", "Unable to add work item. It may already exist.", null, null, null, 400);
                }
            }
            case "work-item-update" -> {
                if (id == null || name.isEmpty()) {
                    sendAdminPanel(exchange, username, firstName, "", "Invalid work item update.", null, id, null, 400);
                    return;
                }
                WorkItemDef existing = findWorkItemDefById(id);
                if (existing == null) {
                    sendAdminPanel(exchange, username, firstName, "", "Work item not found.", null, null, null, 404);
                    return;
                }
                try {
                    updateWorkItemDef(id, name);
                    if (!existing.name.equals(name)) {
                        renameWorkItemAcrossOptions(existing.name, name);
                    }
                    redirect(exchange, "/admin-panel");
                } catch (SQLException ex) {
                    sendAdminPanel(exchange, username, firstName, "", "Unable to update work item. Name may already exist.", null, id, null, 400);
                }
            }
            case "work-item-delete" -> {
                if (id == null) {
                    sendAdminPanel(exchange, username, firstName, "", "Invalid work item.", null, null, null, 400);
                    return;
                }
                WorkItemDef existing = findWorkItemDefById(id);
                if (existing == null) {
                    redirect(exchange, "/admin-panel");
                    return;
                }
                deleteWorkItemDef(id);
                deleteWorkItemAcrossOptions(existing.name);
                redirect(exchange, "/admin-panel");
            }
            case "status-add" -> {
                if (label.isEmpty()) {
                    sendAdminPanel(exchange, username, firstName, "", "Status label is required.", null, null, null, 400);
                    return;
                }
                int percent = parsePercent(percentParam, 0);
                try {
                    insertStatusDef(label, percent, nextStatusSortOrder());
                    redirect(exchange, "/admin-panel");
                } catch (SQLException ex) {
                    sendAdminPanel(exchange, username, firstName, "", "Unable to add status. It may already exist.", null, null, null, 400);
                }
            }
            case "status-update" -> {
                if (id == null || label.isEmpty()) {
                    sendAdminPanel(exchange, username, firstName, "", "Invalid status update.", null, null, id, 400);
                    return;
                }
                StatusDef existing = findStatusDefById(id);
                if (existing == null) {
                    sendAdminPanel(exchange, username, firstName, "", "Status not found.", null, null, null, 404);
                    return;
                }
                int percent = parsePercent(percentParam, existing.percentValue);
                try {
                    updateStatusDef(id, label, percent);
                    if (!existing.label.equals(label)) {
                        renameStatusAcrossOptions(existing.label, label);
                    }
                    redirect(exchange, "/admin-panel");
                } catch (SQLException ex) {
                    sendAdminPanel(exchange, username, firstName, "", "Unable to update status. Label may already exist.", null, null, id, 400);
                }
            }
            case "status-delete" -> {
                if (id == null) {
                    sendAdminPanel(exchange, username, firstName, "", "Invalid status.", null, null, null, 400);
                    return;
                }
                java.util.List<StatusDef> statuses = listStatusDefs();
                if (statuses.size() <= 1) {
                    sendAdminPanel(exchange, username, firstName, "", "At least one status option is required.", null, null, null, 400);
                    return;
                }
                StatusDef existing = findStatusDefById(id);
                if (existing == null) {
                    redirect(exchange, "/admin-panel");
                    return;
                }
                StatusDef replacement = statuses.stream().filter(s -> s.id != existing.id).findFirst().orElse(null);
                deleteStatusDef(id);
                if (replacement != null) {
                    renameStatusAcrossOptions(existing.label, replacement.label);
                }
                redirect(exchange, "/admin-panel");
            }
            default -> sendAdminPanel(exchange, username, firstName, "", "Unknown action.", null, null, null, 400);
        }
    }

    private static int parsePercent(String value, int fallback) {
        try {
            int percent = Integer.parseInt(value);
            return Math.max(0, Math.min(100, percent));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static void sendAdminPanel(HttpExchange exchange, String username, String firstName, String labelValue, String message, Integer editId, Integer editWorkItemId, Integer editStatusId, int statusCode) throws IOException {
        try {
            sendHtmlResponse(exchange, statusCode, buildAdminPanelPage(
                    username,
                    firstName,
                    labelValue,
                    listNavOptions(),
                    listWorkItemDefs(),
                    listStatusDefs(),
                    message,
                    editId,
                    editWorkItemId,
                    editStatusId
            ));
        } catch (SQLException ex) {
            sendHtmlResponse(exchange, 500, buildErrorPage("Unable to load the admin panel right now."));
        }
    }

    private static void handleAdminPanelDelete(HttpExchange exchange, String username, String firstName, String idParam, java.util.List<NavOption> navOptions) throws IOException {
        Integer optionId = parseNavOptionId(idParam);
        if (optionId == null) {
            sendAdminPanel(exchange, username, firstName, "", "Invalid navigation option.", null, null, null, 400);
            return;
        }

        try {
            NavOption option = findNavOptionById(optionId);
            if (option == null) {
                sendAdminPanel(exchange, username, firstName, "", "The navigation option was not found.", null, null, null, 404);
                return;
            }
            deleteNavOption(optionId);
            resetUsersRoleByLabel(option.label);
            sendAdminPanel(exchange, username, firstName, "", "Navigation option \"" + option.label + "\" deleted.", null, null, null, 200);
        } catch (SQLException ex) {
            sendAdminPanel(exchange, username, firstName, "", "Unable to delete the navigation option. Please try again later.", null, null, null, 500);
        }
    }

    private static void handleAdminPanelUpdate(HttpExchange exchange, String username, String firstName, String idParam, String label, java.util.List<NavOption> navOptions) throws IOException {
        Integer optionId = parseNavOptionId(idParam);
        if (optionId == null) {
            sendAdminPanel(exchange, username, firstName, label, "Invalid navigation option.", optionId, null, null, 400);
            return;
        }

        if (label.isEmpty()) {
            sendAdminPanel(exchange, username, firstName, label, "Please enter a name for the navigation option.", optionId, null, null, 400);
            return;
        }

        try {
            NavOption existing = findNavOptionById(optionId);
            if (existing == null) {
                sendAdminPanel(exchange, username, firstName, "", "The navigation option was not found.", null, null, null, 404);
                return;
            }

            if (!existing.label.equalsIgnoreCase(label)) {
                updateNavOption(optionId, label);
                updateUsersRoleByLabel(existing.label, label);
            }

            redirect(exchange, "/admin-panel");
        } catch (SQLException ex) {
            String message = ex.getMessage();
            if (message != null && message.contains("UNIQUE")) {
                sendAdminPanel(exchange, username, firstName, label, "A navigation option with that name already exists.", optionId, null, null, 400);
            } else {
                sendAdminPanel(exchange, username, firstName, label, "Unable to update the navigation option. Please try again later.", optionId, null, null, 500);
            }
        }
    }

    private static Integer parseNavOptionId(String idParam) {
        if (idParam == null || idParam.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(idParam.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static void handleStatus(HttpExchange exchange) throws IOException {
        String username = getSessionUsername(exchange);
        if (username == null) {
            redirect(exchange, "/");
            return;
        }

        try {
            UserRecord user = findUserByUsername(username);
            if (user == null) {
                redirect(exchange, "/logout");
                return;
            }
            if (!user.isAdmin) {
                redirect(exchange, resolveHomePath(user, listNavOptions()));
                return;
            }

            java.util.List<NavOption> navOptions = listNavOptions();
            java.util.List<OptionProgress> progressList = listOptionProgress();

            Map<String, String> q = parseQueryString(exchange.getRequestURI().getQuery());
            Integer selectedId = parseNavOptionId(q.get("id"));
            OptionProgress selected = null;
            if (selectedId != null) {
                for (OptionProgress progress : progressList) {
                    if (progress.option.id == selectedId) {
                        selected = progress;
                        break;
                    }
                }
            }

            sendHtmlResponse(exchange, 200, buildStatusPage(username, user.firstName, navOptions, progressList, selected));
        } catch (SQLException ex) {
            sendHtmlResponse(exchange, 500, buildErrorPage("Unable to load status right now."));
        }
    }

    private static void handleStatusExport(HttpExchange exchange) throws IOException {
        String username = getSessionUsername(exchange);
        if (username == null) {
            redirect(exchange, "/");
            return;
        }

        try {
            UserRecord user = findUserByUsername(username);
            if (user == null) {
                redirect(exchange, "/logout");
                return;
            }
            if (!user.isAdmin) {
                redirect(exchange, resolveHomePath(user, listNavOptions()));
                return;
            }

            java.util.List<OptionProgress> progressList = listOptionProgress();
            byte[] pdfBytes = buildStatusPdf(progressList);

            exchange.getResponseHeaders().set("Content-Type", "application/pdf");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"status-report.pdf\"");
            exchange.sendResponseHeaders(200, pdfBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(pdfBytes);
            }
        } catch (SQLException | DocumentException ex) {
            sendHtmlResponse(exchange, 500, buildErrorPage("Unable to export status PDF right now."));
        }
    }

    private static byte[] buildStatusPdf(java.util.List<OptionProgress> progressList) throws DocumentException, IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document document = new Document();
        PdfWriter.getInstance(document, baos);
        document.open();

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
        Font headingFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
        Font normalFont = FontFactory.getFont(FontFactory.HELVETICA, 11);
        Font tableHeaderFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);

        document.add(new Paragraph("Status Report", titleFont));
        document.add(new Paragraph("Generated: " + java.time.LocalDateTime.now().toString().replace('T', ' ').substring(0, 19), normalFont));
        document.add(new Paragraph(" "));

        document.add(new Paragraph("Overall Progress", headingFont));
        document.add(new Paragraph(" "));

        PdfPTable overviewTable = new PdfPTable(2);
        overviewTable.setWidthPercentage(100);
        overviewTable.setWidths(new float[]{3f, 2f});
        overviewTable.addCell(pdfHeaderCell("Venue", tableHeaderFont));
        overviewTable.addCell(pdfHeaderCell("Progress", tableHeaderFont));

        if (progressList.isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase("No venues available.", normalFont));
            empty.setColspan(2);
            empty.setPadding(6);
            overviewTable.addCell(empty);
        } else {
            for (OptionProgress progress : progressList) {
                overviewTable.addCell(pdfBodyCell(progress.option.label, normalFont));
                overviewTable.addCell(pdfBodyCell(progress.overallPercent + "% overall", normalFont));
            }
        }
        document.add(overviewTable);
        document.add(new Paragraph(" "));

        for (OptionProgress progress : progressList) {
            document.add(new Paragraph(progress.option.label + " — Overall progress: " + progress.overallPercent + "%", headingFont));
            document.add(new Paragraph(" "));

            PdfPTable detailTable = new PdfPTable(3);
            detailTable.setWidthPercentage(100);
            detailTable.setWidths(new float[]{3f, 2f, 2f});
            detailTable.addCell(pdfHeaderCell("Work Item", tableHeaderFont));
            detailTable.addCell(pdfHeaderCell("Status", tableHeaderFont));
            detailTable.addCell(pdfHeaderCell("Progress", tableHeaderFont));

            for (WorkItem item : progress.workItems) {
                int pct = statusToPercent(item.status);
                detailTable.addCell(pdfBodyCell(item.name, normalFont));
                detailTable.addCell(pdfBodyCell(item.status, normalFont));
                detailTable.addCell(pdfBodyCell(pct + "%", normalFont));
            }
            document.add(detailTable);
            document.add(new Paragraph(" "));
        }

        document.close();
        return baos.toByteArray();
    }

    private static PdfPCell pdfHeaderCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        cell.setPadding(6);
        cell.setGrayFill(0.9f);
        return cell;
    }

    private static PdfPCell pdfBodyCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        cell.setPadding(6);
        return cell;
    }

    private static class WorkItemDef {
        final int id;
        final String name;
        final int sortOrder;

        WorkItemDef(int id, String name, int sortOrder) {
            this.id = id;
            this.name = name;
            this.sortOrder = sortOrder;
        }
    }

    private static class StatusDef {
        final int id;
        final String label;
        final int percentValue;
        final int sortOrder;

        StatusDef(int id, String label, int percentValue, int sortOrder) {
            this.id = id;
            this.label = label;
            this.percentValue = percentValue;
            this.sortOrder = sortOrder;
        }
    }

    private static void handleCustomPage(HttpExchange exchange) throws IOException {
        String username = getSessionUsername(exchange);
        if (username == null) {
            redirect(exchange, "/");
            return;
        }

        try {
            UserRecord user = findUserByUsername(username);
            if (user == null) {
                redirect(exchange, "/logout");
                return;
            }
            if (user.mustChangePassword) {
                redirect(exchange, "/change-password");
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            Map<String, String> q = parseQueryString(query);
            String idParam = q.get("id");
            if (idParam == null || idParam.isBlank()) {
                sendHtmlResponse(exchange, 400, buildErrorPage("Invalid page request."));
                return;
            }

            int optionId;
            try {
                optionId = Integer.parseInt(idParam.trim());
            } catch (NumberFormatException ex) {
                sendHtmlResponse(exchange, 400, buildErrorPage("Invalid page request."));
                return;
            }

            NavOption option = findNavOptionById(optionId);
            if (option == null) {
                sendHtmlResponse(exchange, 404, buildErrorPage("The requested page was not found."));
                return;
            }

            java.util.List<NavOption> navOptions = listNavOptions();
            if (!canAccessCustomPage(user, option)) {
                redirect(exchange, resolveHomePath(user, navOptions));
                return;
            }

            ensureDefaultWorkItems(optionId);

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleCustomPageStatusPost(exchange, optionId);
                return;
            }

            java.util.List<WorkItem> workItems = listWorkItems(optionId);
            java.util.List<StatusDef> statusDefs = listStatusDefs();
            sendHtmlResponse(exchange, 200, buildCustomPage(user.firstName, username, user.role, option, navOptions, workItems, statusDefs));
        } catch (SQLException ex) {
            sendHtmlResponse(exchange, 500, buildErrorPage("Unable to load the page right now."));
        }
    }

    private static void handleCustomPageStatusPost(HttpExchange exchange, int optionId) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = parseFormData(requestBody);
        String itemName = form.getOrDefault("itemName", "").trim();
        String status = form.getOrDefault("status", "").trim();

        try {
            if (itemName.isEmpty() || !isValidWorkItemName(itemName) || !isValidWorkItemStatus(status)) {
                redirect(exchange, "/page?id=" + optionId);
                return;
            }
            updateWorkItemStatus(optionId, itemName, status);
        } catch (SQLException ignored) {
            // fall through to redirect
        }
        redirect(exchange, "/page?id=" + optionId);
    }

    private static boolean isValidWorkItemName(String itemName) throws SQLException {
        for (WorkItemDef item : listWorkItemDefs()) {
            if (item.name.equals(itemName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidWorkItemStatus(String status) throws SQLException {
        for (StatusDef def : listStatusDefs()) {
            if (def.label.equals(status)) {
                return true;
            }
        }
        return false;
    }

    // --- Users list and edit UI/handlers ---
    private static void handleUsers(HttpExchange exchange) throws IOException {
        String currentUsername = getSessionUsername(exchange);
        if (currentUsername == null) {
            redirect(exchange, "/");
            return;
        }

        try {
            UserRecord current = findUserByUsername(currentUsername);
            if (current == null) {
                redirect(exchange, "/logout");
                return;
            }
            if (!isAdminRole(current.role) && !isStandardUserRole(current.role)) {
                java.util.List<NavOption> navOptions = listNavOptions();
                redirect(exchange, resolveHomePath(current, navOptions));
                return;
            }

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleEditUserPost(exchange, current.isAdmin);
                return;
            }

            // GET: list users and optionally show edit form for ?edit=username
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> q = new LinkedHashMap<>();
            if (query != null) {
                for (String part : query.split("&")) {
                    String[] kv = part.split("=", 2);
                    if (kv.length == 2) q.put(urlDecode(kv[0]), urlDecode(kv[1]));
                }
            }
            String editUsername = q.get("edit");
            String notice = q.get("notice");

            var users = listAllUsers();
            Map<String, String> editData = null;
            if (editUsername != null) {
                for (UserEntry u : users) {
                    if (u.username.equals(editUsername)) {
                        editData = new LinkedHashMap<>();
                        editData.put("firstName", u.firstName);
                        editData.put("lastName", u.lastName);
                        editData.put("email", u.email);
                        editData.put("username", u.username);
                        editData.put("isAdmin", u.isAdmin ? "1" : "0");
                        editData.put("role", u.role != null ? u.role : (u.isAdmin ? "admin" : "user"));
                        break;
                    }
                }
            }

            sendHtmlResponse(exchange, 200, buildUsersPage(users, editData, current.isAdmin, current.role, currentUsername, current.firstName, notice, listNavOptions()));
        } catch (SQLException ex) {
            sendHtmlResponse(exchange, 500, buildErrorPage("Unable to load users right now."));
        }
    }

    private static void handleEditUserPost(HttpExchange exchange, boolean currentIsAdmin) throws IOException {
        // Only admins can modify users
        if (!currentIsAdmin) {
            sendHtmlResponse(exchange, 403, buildErrorPage("You do not have permission to modify users."));
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = parseFormData(body);
        String action = form.getOrDefault("action", "update").trim().toLowerCase();
        String username = form.getOrDefault("username", "").trim();
        String currentUser = getSessionUsername(exchange);

        if (username.isEmpty()) {
            sendHtmlResponse(exchange, 400, buildErrorPage("Invalid user request."));
            return;
        }

        if ("delete".equals(action)) {
            handleDeleteUserPost(exchange, username, currentUser);
            return;
        }

        if ("toggle-enabled".equals(action)) {
            handleToggleUserEnabledPost(exchange, username, currentUser);
            return;
        }

        if ("update-role".equals(action)) {
            handleUpdateUserRolePost(exchange, form, username, currentUser);
            return;
        }

        String firstName = form.getOrDefault("firstName", "").trim();
        String lastName = form.getOrDefault("lastName", "").trim();
        String email = form.getOrDefault("email", "").trim();
        String role = form.getOrDefault("role", "user").trim();

        if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty()) {
            try {
                var users = listAllUsers();
                var navOptions = listNavOptions();
                String curFirst = "";
                String curRole = "user";
                try {
                    UserRecord cur = findUserByUsername(currentUser);
                    if (cur != null) {
                        curFirst = cur.firstName;
                        curRole = cur.role;
                    }
                } catch (SQLException e) {
                    curFirst = "";
                }
                sendHtmlResponse(exchange, 400, buildUsersPage(users, form, true, curRole, currentUser, curFirst, "All fields are required.", navOptions));
            } catch (SQLException ex) {
                sendHtmlResponse(exchange, 500, buildErrorPage("Unable to update user."));
            }
            return;
        }

        try {
            var navOptions = listNavOptions();
            if (!isValidRole(role, navOptions)) {
                var users = listAllUsers();
                UserRecord cur = findUserByUsername(currentUser);
                sendHtmlResponse(exchange, 400, buildUsersPage(users, form, true, cur != null ? cur.role : "admin", currentUser, cur != null ? cur.firstName : "", "Invalid role selected.", navOptions));
                return;
            }

            boolean updated = updateUserDetails(username, firstName, lastName, email, role);
            if (updated) {
                redirect(exchange, "/users?edit=" + java.net.URLEncoder.encode(username, StandardCharsets.UTF_8));
                return;
            } else {
                var users = listAllUsers();
                UserRecord cur = findUserByUsername(currentUser);
                sendHtmlResponse(exchange, 500, buildUsersPage(users, form, true, cur != null ? cur.role : "admin", currentUser, cur != null ? cur.firstName : "", "No changes were applied.", navOptions));
                return;
            }
        } catch (SQLException ex) {
            String msg = ex.getMessage();
            try {
                var users = listAllUsers();
                var navOptions = listNavOptions();
                String feedback = (msg != null && msg.contains("UNIQUE")) ? "Email already in use." : "Unable to save changes.";
                UserRecord cur = findUserByUsername(currentUser);
                sendHtmlResponse(exchange, 500, buildUsersPage(users, form, true, cur != null ? cur.role : "admin", currentUser, cur != null ? cur.firstName : "", feedback, navOptions));
            } catch (SQLException inner) {
                sendHtmlResponse(exchange, 500, buildErrorPage("Unable to save changes."));
            }
        }
    }

    private static void handleDeleteUserPost(HttpExchange exchange, String username, String currentUser) throws IOException {
        if (username.equals(currentUser)) {
            redirectUsersNotice(exchange, "You cannot delete your own account.");
            return;
        }
        if (DEFAULT_ADMIN_USERNAME.equals(username)) {
            redirectUsersNotice(exchange, "The default admin account cannot be deleted.");
            return;
        }

        try {
            UserEntry target = findUserEntryByUsername(username);
            if (target == null) {
                redirectUsersNotice(exchange, "User not found.");
                return;
            }
            deleteUser(username);
            sessions.entrySet().removeIf(entry -> entry.getValue().username.equals(username));
            redirectUsersNotice(exchange, "User \"" + target.firstName + " " + target.lastName + "\" deleted.");
        } catch (SQLException ex) {
            sendHtmlResponse(exchange, 500, buildErrorPage("Unable to delete the user."));
        }
    }

    private static void handleToggleUserEnabledPost(HttpExchange exchange, String username, String currentUser) throws IOException {
        if (username.equals(currentUser)) {
            redirectUsersNotice(exchange, "You cannot disable your own account.");
            return;
        }
        if (DEFAULT_ADMIN_USERNAME.equals(username)) {
            redirectUsersNotice(exchange, "The default admin account cannot be disabled.");
            return;
        }

        try {
            UserEntry target = findUserEntryByUsername(username);
            if (target == null) {
                redirectUsersNotice(exchange, "User not found.");
                return;
            }
            boolean newEnabled = !target.enabled;
            setUserEnabled(username, newEnabled);
            if (!newEnabled) {
                sessions.entrySet().removeIf(entry -> entry.getValue().username.equals(username));
            }
            String statusLabel = newEnabled ? "enabled" : "disabled";
            redirectUsersNotice(exchange, "User \"" + target.firstName + " " + target.lastName + "\" " + statusLabel + ".");
        } catch (SQLException ex) {
            sendHtmlResponse(exchange, 500, buildErrorPage("Unable to update user status."));
        }
    }

    private static void handleUpdateUserRolePost(HttpExchange exchange, Map<String, String> form, String username, String currentUser) throws IOException {
        String role = form.getOrDefault("role", "user").trim();

        try {
            var navOptions = listNavOptions();
            if (!isValidRole(role, navOptions)) {
                redirectUsersNotice(exchange, "Invalid role selected.");
                return;
            }

            UserEntry target = findUserEntryByUsername(username);
            if (target == null) {
                redirectUsersNotice(exchange, "User not found.");
                return;
            }

            updateUserRole(username, role);
            redirectUsersNotice(exchange, "Role updated for \"" + target.firstName + " " + target.lastName + "\".");
        } catch (SQLException ex) {
            sendHtmlResponse(exchange, 500, buildErrorPage("Unable to update user role."));
        }
    }

    private static void redirectUsersNotice(HttpExchange exchange, String notice) throws IOException {
        redirect(exchange, "/users?notice=" + java.net.URLEncoder.encode(notice, StandardCharsets.UTF_8));
    }

    private static class NavOption {
        final int id;
        final String label;

        NavOption(int id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private static java.util.List<NavOption> listNavOptions() throws SQLException {
        String sql = "SELECT id, label FROM nav_options ORDER BY created_at ASC";
        var list = new java.util.ArrayList<NavOption>();
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql);
             var rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(new NavOption(rs.getInt("id"), rs.getString("label")));
            }
        }
        return list;
    }

    private static NavOption findNavOptionById(int id) throws SQLException {
        String sql = "SELECT id, label FROM nav_options WHERE id = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new NavOption(rs.getInt("id"), rs.getString("label"));
            }
        }
    }

    private static void saveNavOption(String label) throws SQLException {
        String sql = "INSERT INTO nav_options (label, created_at) VALUES (?, ?)";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, label);
            stmt.setString(2, Instant.now().toString());
            stmt.executeUpdate();
        }
        NavOption created = findNavOptionByLabelExact(label);
        if (created != null) {
            ensureDefaultWorkItems(created.id);
        }
    }

    private static NavOption findNavOptionByLabelExact(String label) throws SQLException {
        String sql = "SELECT id, label FROM nav_options WHERE label = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, label);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new NavOption(rs.getInt("id"), rs.getString("label"));
            }
        }
    }

    private static void updateNavOption(int id, String label) throws SQLException {
        String sql = "UPDATE nav_options SET label = ? WHERE id = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, label);
            stmt.setInt(2, id);
            stmt.executeUpdate();
        }
        syncWorkItemOptionLabel(new NavOption(id, label));
    }

    private static void deleteNavOption(int id) throws SQLException {
        try (Connection statusDb = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement deleteItems = statusDb.prepareStatement("DELETE FROM nav_work_items WHERE nav_option_id = ?")) {
            deleteItems.setInt(1, id);
            deleteItems.executeUpdate();
        }
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement deleteOption = connection.prepareStatement("DELETE FROM nav_options WHERE id = ?")) {
            deleteOption.setInt(1, id);
            deleteOption.executeUpdate();
        }
    }

    private static class WorkItem {
        final String name;
        final String status;

        WorkItem(String name, String status) {
            this.name = name;
            this.status = status;
        }
    }

    private static class OptionProgress {
        final NavOption option;
        final java.util.List<WorkItem> workItems;
        final int overallPercent;

        OptionProgress(NavOption option, java.util.List<WorkItem> workItems, int overallPercent) {
            this.option = option;
            this.workItems = workItems;
            this.overallPercent = overallPercent;
        }
    }

    private static void ensureDefaultWorkItemsForAllNavOptions() throws SQLException {
        for (NavOption option : listNavOptions()) {
            ensureDefaultWorkItems(option);
        }
    }

    private static void ensureDefaultWorkItems(int navOptionId) throws SQLException {
        NavOption option = findNavOptionById(navOptionId);
        if (option != null) {
            ensureDefaultWorkItems(option);
        }
    }

    private static void ensureDefaultWorkItems(NavOption option) throws SQLException {
        java.util.List<WorkItemDef> defs = listWorkItemDefs();
        String defaultStatus = getDefaultStatusLabel();
        String sql = "INSERT OR IGNORE INTO nav_work_items (nav_option_id, option_label, item_name, status, updated_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            String now = Instant.now().toString();
            for (WorkItemDef def : defs) {
                stmt.setInt(1, option.id);
                stmt.setString(2, option.label);
                stmt.setString(3, def.name);
                stmt.setString(4, defaultStatus);
                stmt.setString(5, now);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
        syncWorkItemOptionLabel(option);
        removeOrphanedWorkItems(option.id, defs);
    }

    private static void removeOrphanedWorkItems(int navOptionId, java.util.List<WorkItemDef> defs) throws SQLException {
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < defs.size(); i++) {
            if (i > 0) {
                placeholders.append(",");
            }
            placeholders.append("?");
        }
        String sql = defs.isEmpty()
                ? "DELETE FROM nav_work_items WHERE nav_option_id = ?"
                : "DELETE FROM nav_work_items WHERE nav_option_id = ? AND item_name NOT IN (" + placeholders + ")";
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, navOptionId);
            for (int i = 0; i < defs.size(); i++) {
                stmt.setString(i + 2, defs.get(i).name);
            }
            stmt.executeUpdate();
        }
    }

    private static String getDefaultStatusLabel() throws SQLException {
        java.util.List<StatusDef> statuses = listStatusDefs();
        return statuses.isEmpty() ? "Not started" : statuses.get(0).label;
    }

    private static void syncWorkItemOptionLabel(NavOption option) throws SQLException {
        String sql = "UPDATE nav_work_items SET option_label = ? WHERE nav_option_id = ?";
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, option.label);
            stmt.setInt(2, option.id);
            stmt.executeUpdate();
        }
    }

    private static java.util.List<WorkItem> listWorkItems(int navOptionId) throws SQLException {
        ensureDefaultWorkItems(navOptionId);
        String sql = "SELECT item_name, status FROM nav_work_items WHERE nav_option_id = ?";
        var byName = new LinkedHashMap<String, WorkItem>();
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, navOptionId);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    byName.put(rs.getString("item_name"), new WorkItem(rs.getString("item_name"), rs.getString("status")));
                }
            }
        }

        var ordered = new java.util.ArrayList<WorkItem>();
        for (WorkItemDef def : listWorkItemDefs()) {
            WorkItem item = byName.get(def.name);
            if (item != null) {
                ordered.add(item);
            }
        }
        return ordered;
    }

    private static void updateWorkItemStatus(int navOptionId, String itemName, String status) throws SQLException {
        String sql = "UPDATE nav_work_items SET status = ?, updated_at = ? WHERE nav_option_id = ? AND item_name = ?";
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, status);
            stmt.setString(2, Instant.now().toString());
            stmt.setInt(3, navOptionId);
            stmt.setString(4, itemName);
            stmt.executeUpdate();
        }
    }

    private static int statusToPercent(String status) {
        if (status == null) {
            return 0;
        }
        try {
            for (StatusDef def : listStatusDefs()) {
                if (def.label.equals(status)) {
                    return def.percentValue;
                }
            }
        } catch (SQLException ignored) {
            // fall through
        }
        return 0;
    }

    private static java.util.List<WorkItemDef> listWorkItemDefs() throws SQLException {
        String sql = "SELECT id, name, sort_order FROM work_item_defs ORDER BY sort_order ASC, id ASC";
        var list = new java.util.ArrayList<WorkItemDef>();
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql);
             var rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(new WorkItemDef(rs.getInt("id"), rs.getString("name"), rs.getInt("sort_order")));
            }
        }
        return list;
    }

    private static java.util.List<StatusDef> listStatusDefs() throws SQLException {
        String sql = "SELECT id, label, percent_value, sort_order FROM status_defs ORDER BY sort_order ASC, id ASC";
        var list = new java.util.ArrayList<StatusDef>();
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql);
             var rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(new StatusDef(rs.getInt("id"), rs.getString("label"), rs.getInt("percent_value"), rs.getInt("sort_order")));
            }
        }
        return list;
    }

    private static WorkItemDef findWorkItemDefById(int id) throws SQLException {
        String sql = "SELECT id, name, sort_order FROM work_item_defs WHERE id = ?";
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new WorkItemDef(rs.getInt("id"), rs.getString("name"), rs.getInt("sort_order"));
            }
        }
    }

    private static StatusDef findStatusDefById(int id) throws SQLException {
        String sql = "SELECT id, label, percent_value, sort_order FROM status_defs WHERE id = ?";
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new StatusDef(rs.getInt("id"), rs.getString("label"), rs.getInt("percent_value"), rs.getInt("sort_order"));
            }
        }
    }

    private static int nextWorkItemSortOrder() throws SQLException {
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             Statement stmt = connection.createStatement();
             var rs = stmt.executeQuery("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM work_item_defs")) {
            return rs.next() ? rs.getInt(1) : 1;
        }
    }

    private static int nextStatusSortOrder() throws SQLException {
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             Statement stmt = connection.createStatement();
             var rs = stmt.executeQuery("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM status_defs")) {
            return rs.next() ? rs.getInt(1) : 1;
        }
    }

    private static void insertWorkItemDef(String name, int sortOrder) throws SQLException {
        String sql = "INSERT INTO work_item_defs (name, sort_order) VALUES (?, ?)";
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setInt(2, sortOrder);
            stmt.executeUpdate();
        }
    }

    private static void updateWorkItemDef(int id, String name) throws SQLException {
        String sql = "UPDATE work_item_defs SET name = ? WHERE id = ?";
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setInt(2, id);
            stmt.executeUpdate();
        }
    }

    private static void deleteWorkItemDef(int id) throws SQLException {
        String sql = "DELETE FROM work_item_defs WHERE id = ?";
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, id);
            stmt.executeUpdate();
        }
    }

    private static void insertStatusDef(String label, int percent, int sortOrder) throws SQLException {
        String sql = "INSERT INTO status_defs (label, percent_value, sort_order) VALUES (?, ?, ?)";
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, label);
            stmt.setInt(2, percent);
            stmt.setInt(3, sortOrder);
            stmt.executeUpdate();
        }
    }

    private static void updateStatusDef(int id, String label, int percent) throws SQLException {
        String sql = "UPDATE status_defs SET label = ?, percent_value = ? WHERE id = ?";
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, label);
            stmt.setInt(2, percent);
            stmt.setInt(3, id);
            stmt.executeUpdate();
        }
    }

    private static void deleteStatusDef(int id) throws SQLException {
        String sql = "DELETE FROM status_defs WHERE id = ?";
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, id);
            stmt.executeUpdate();
        }
    }

    private static void seedWorkItemToAllNavOptions(String itemName) throws SQLException {
        String defaultStatus = getDefaultStatusLabel();
        String sql = "INSERT OR IGNORE INTO nav_work_items (nav_option_id, option_label, item_name, status, updated_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            String now = Instant.now().toString();
            for (NavOption option : listNavOptions()) {
                stmt.setInt(1, option.id);
                stmt.setString(2, option.label);
                stmt.setString(3, itemName);
                stmt.setString(4, defaultStatus);
                stmt.setString(5, now);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private static void renameWorkItemAcrossOptions(String oldName, String newName) throws SQLException {
        String sql = "UPDATE nav_work_items SET item_name = ?, updated_at = ? WHERE item_name = ?";
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, newName);
            stmt.setString(2, Instant.now().toString());
            stmt.setString(3, oldName);
            stmt.executeUpdate();
        }
    }

    private static void deleteWorkItemAcrossOptions(String itemName) throws SQLException {
        String sql = "DELETE FROM nav_work_items WHERE item_name = ?";
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, itemName);
            stmt.executeUpdate();
        }
    }

    private static void renameStatusAcrossOptions(String oldLabel, String newLabel) throws SQLException {
        String sql = "UPDATE nav_work_items SET status = ?, updated_at = ? WHERE status = ?";
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, newLabel);
            stmt.setString(2, Instant.now().toString());
            stmt.setString(3, oldLabel);
            stmt.executeUpdate();
        }
    }

    private static int calculateOverallProgress(java.util.List<WorkItem> workItems) {
        if (workItems == null || workItems.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (WorkItem item : workItems) {
            total += statusToPercent(item.status);
        }
        return Math.round(total / (float) workItems.size());
    }

    private static java.util.List<OptionProgress> listOptionProgress() throws SQLException {
        var result = new java.util.ArrayList<OptionProgress>();
        for (NavOption option : listNavOptions()) {
            ensureDefaultWorkItems(option);
            java.util.List<WorkItem> items = listWorkItems(option.id);
            result.add(new OptionProgress(option, items, calculateOverallProgress(items)));
        }
        return result;
    }

    private static void updateUsersRoleByLabel(String oldLabel, String newLabel) throws SQLException {
        String sql = "UPDATE users SET role = ? WHERE role = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, newLabel);
            stmt.setString(2, oldLabel);
            stmt.executeUpdate();
        }
    }

    private static void resetUsersRoleByLabel(String label) throws SQLException {
        String sql = "UPDATE users SET role = 'user', is_admin = 0 WHERE role = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, label);
            stmt.executeUpdate();
        }
    }

    private static Map<String, String> parseQueryString(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return result;
        }
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                result.put(urlDecode(kv[0]), urlDecode(kv[1]));
            }
        }
        return result;
    }

    private static String buildSidebarHtml(String username, String userRole, java.util.List<NavOption> navOptions, String navItemClass) {
        StringBuilder nav = new StringBuilder();
        if (isAdminRole(userRole)) {
            nav.append("<a class=\"").append(navItemClass).append("\" href=\"/dashboard\">Dashboard</a>");
            nav.append("<a class=\"").append(navItemClass).append("\" href=\"/users\">Users</a>");
            nav.append("<a class=\"").append(navItemClass).append("\" href=\"/admin-panel\">Admin Panel</a>");
            nav.append("<a class=\"").append(navItemClass).append("\" href=\"/status\">Status</a>");
            for (NavOption option : navOptions) {
                nav.append("<a class=\"").append(navItemClass).append("\" href=\"/page?id=").append(option.id).append("\">")
                        .append(escapeHtml(option.label)).append("</a>");
            }
        } else if (isStandardUserRole(userRole)) {
            nav.append("<a class=\"").append(navItemClass).append("\" href=\"/dashboard\">Dashboard</a>");
            nav.append("<a class=\"").append(navItemClass).append("\" href=\"/users\">Users</a>");
        } else {
            NavOption matched = findNavOptionByLabel(userRole, navOptions);
            if (matched != null) {
                nav.append("<a class=\"").append(navItemClass).append("\" href=\"/page?id=").append(matched.id).append("\">")
                        .append(escapeHtml(matched.label)).append("</a>");
            }
        }
        return "<aside class=\"sidebar\"><h2>Navigation</h2><nav>" + nav + "</nav><hr/>" +
                "<p style=\"opacity:0.8;font-size:0.9rem;\">Logged in as " + escapeHtml(username) + "</p></aside>";
    }

    private static boolean isAdminRole(String role) {
        return "admin".equalsIgnoreCase(role);
    }

    private static boolean isStandardUserRole(String role) {
        return role == null || role.isEmpty() || "user".equalsIgnoreCase(role);
    }

    private static boolean isValidRole(String role, java.util.List<NavOption> navOptions) {
        if (isAdminRole(role) || isStandardUserRole(role)) {
            return true;
        }
        return findNavOptionByLabel(role, navOptions) != null;
    }

    private static NavOption findNavOptionByLabel(String label, java.util.List<NavOption> navOptions) {
        if (label == null) {
            return null;
        }
        for (NavOption option : navOptions) {
            if (option.label.equalsIgnoreCase(label)) {
                return option;
            }
        }
        return null;
    }

    private static boolean canAccessCustomPage(UserRecord user, NavOption option) {
        if (isAdminRole(user.role)) {
            return true;
        }
        if (isStandardUserRole(user.role)) {
            return false;
        }
        return option.label.equalsIgnoreCase(user.role);
    }

    private static String resolveHomePath(UserRecord user, java.util.List<NavOption> navOptions) {
        if (isAdminRole(user.role) || isStandardUserRole(user.role)) {
            return "/dashboard";
        }
        NavOption matched = findNavOptionByLabel(user.role, navOptions);
        return matched != null ? "/page?id=" + matched.id : "/dashboard";
    }

    private static String formatRoleDisplay(String role) {
        if (role == null || role.isEmpty() || "user".equalsIgnoreCase(role)) {
            return "User";
        }
        if ("admin".equalsIgnoreCase(role)) {
            return "Admin";
        }
        return role;
    }

    private static String buildRoleOptionsHtml(String selectedRole, java.util.List<NavOption> navOptions) {
        String normalizedSelected = selectedRole == null || selectedRole.isEmpty() ? "user" : selectedRole;
        StringBuilder options = new StringBuilder();
        options.append(roleOption("user", "User", normalizedSelected));
        options.append(roleOption("admin", "Admin", normalizedSelected));
        for (NavOption option : navOptions) {
            options.append(roleOption(option.label, option.label, normalizedSelected));
        }
        return options.toString();
    }

    private static String roleOption(String value, String label, String selectedRole) {
        return "<option value=\"" + escapeHtml(value) + "\"" +
                (value.equalsIgnoreCase(selectedRole) ? " selected" : "") +
                ">" + escapeHtml(label) + "</option>";
    }

    private static String sidebarLayoutStyles() {
        return "body{margin:0;font-family:Arial,Helvetica,sans-serif;background:linear-gradient(135deg,#0f172a,#2563eb);color:#f8fafc;}" +
                ".container{display:flex;min-height:100vh;}" +
                ".sidebar{width:260px;padding:1.5rem;background:rgba(255,255,255,0.04);border-right:1px solid rgba(255,255,255,0.04);}" +
                ".nav-item,.user-item{display:block;padding:0.6rem;border-radius:10px;color:#e6eef8;text-decoration:none;margin-bottom:0.35rem;}" +
                ".nav-item:hover,.user-item:hover{background:rgba(255,255,255,0.03);}" +
                ".main{flex:1;padding:2rem;}" +
                "a.button{padding:0.6rem 0.9rem;border-radius:10px;background:#2563eb;color:#fff;text-decoration:none;font-weight:600;display:inline-block;}" +
                "a.button:hover{background:#1d4ed8;}";
    }

    private static class UserEntry {
        final String username;
        final String firstName;
        final String lastName;
        final String email;
        final boolean isAdmin;
        final String role;
        final boolean enabled;

        UserEntry(String username, String firstName, String lastName, String email, boolean isAdmin, String role, boolean enabled) {
            this.username = username;
            this.firstName = firstName;
            this.lastName = lastName;
            this.email = email;
            this.isAdmin = isAdmin;
            this.role = role == null ? "user" : role;
            this.enabled = enabled;
        }
    }

    private static java.util.List<UserEntry> listAllUsers() throws SQLException {
        String sql = "SELECT username, first_name, last_name, email, is_admin, role, is_enabled FROM users ORDER BY created_at DESC";
        var list = new java.util.ArrayList<UserEntry>();
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql);
             var rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(new UserEntry(
                        rs.getString("username"),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getString("email"),
                        rs.getInt("is_admin") == 1,
                        rs.getString("role"),
                        rs.getInt("is_enabled") == 1
                ));
            }
        }
        return list;
    }

    private static UserEntry findUserEntryByUsername(String username) throws SQLException {
        String sql = "SELECT username, first_name, last_name, email, is_admin, role, is_enabled FROM users WHERE username = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new UserEntry(
                        rs.getString("username"),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getString("email"),
                        rs.getInt("is_admin") == 1,
                        rs.getString("role"),
                        rs.getInt("is_enabled") == 1
                );
            }
        }
    }

    private static boolean updateUserRole(String username, String role) throws SQLException {
        boolean isAdmin = isAdminRole(role);
        String sql = "UPDATE users SET is_admin = ?, role = ? WHERE username = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, isAdmin ? 1 : 0);
            stmt.setString(2, role);
            stmt.setString(3, username);
            return stmt.executeUpdate() > 0;
        }
    }

    private static void setUserEnabled(String username, boolean enabled) throws SQLException {
        String sql = "UPDATE users SET is_enabled = ? WHERE username = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, enabled ? 1 : 0);
            stmt.setString(2, username);
            stmt.executeUpdate();
        }
    }

    private static void deleteUser(String username) throws SQLException {
        String sql = "DELETE FROM users WHERE username = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.executeUpdate();
        }
    }

    private static boolean updateUserDetails(String username, String firstName, String lastName, String email, String role) throws SQLException {
        boolean isAdmin = isAdminRole(role);
        String sql = "UPDATE users SET first_name = ?, last_name = ?, email = ?, is_admin = ?, role = ? WHERE username = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, firstName);
            stmt.setString(2, lastName);
            stmt.setString(3, email);
            stmt.setInt(4, isAdmin ? 1 : 0);
            stmt.setString(5, role);
            stmt.setString(6, username);
            int updated = stmt.executeUpdate();
            return updated > 0;
        }
    }

    private static String buildUsersPage(java.util.List<UserEntry> users, Map<String, String> editData, boolean currentIsAdmin, String currentUserRole, String currentUsername, String currentFirstName, String message, java.util.List<NavOption> navOptions) {
        String selectedUsername = editData == null ? null : editData.get("username");

        StringBuilder tableRows = new StringBuilder();
        for (UserEntry u : users) {
            String fullName = escapeHtml(u.firstName) + " " + escapeHtml(u.lastName);
            String email = escapeHtml(u.email);
            String roleSelected = u.role != null && !u.role.isEmpty() ? u.role : (u.isAdmin ? "admin" : "user");
            boolean isSelected = u.username.equals(selectedUsername);

            String roleCell;
            if (currentIsAdmin) {
                roleCell = "<form class=\"role-form\" action=\"/users\" method=\"post\">" +
                        "<input type=\"hidden\" name=\"action\" value=\"update-role\"/>" +
                        "<input type=\"hidden\" name=\"username\" value=\"" + escapeHtml(u.username) + "\"/>" +
                        "<select name=\"role\">" +
                        buildRoleOptionsHtml(roleSelected, navOptions) +
                        "</select>" +
                        "<button type=\"submit\" class=\"btn-sm\">Save</button>" +
                        "</form>";
            } else {
                roleCell = "<select disabled><option selected>" + escapeHtml(formatRoleDisplay(roleSelected)) + "</option></select>";
            }

            String statusCell = u.enabled
                    ? "<span class=\"status-badge status-active\">Active</span>"
                    : "<span class=\"status-badge status-disabled\">Disabled</span>";

            String actionsCell = "";
            if (currentIsAdmin) {
                boolean isSelf = u.username.equals(currentUsername);
                boolean isDefaultAdmin = DEFAULT_ADMIN_USERNAME.equals(u.username);
                StringBuilder actions = new StringBuilder("<div class=\"user-actions\">");
                actions.append("<a class=\"button btn-sm\" href=\"/users?edit=")
                        .append(java.net.URLEncoder.encode(u.username, StandardCharsets.UTF_8)).append("\">Edit</a>");

                if (!isSelf && !isDefaultAdmin) {
                    String toggleLabel = u.enabled ? "Disable" : "Enable";
                    actions.append("<form class=\"user-action-form\" action=\"/users\" method=\"post\">")
                            .append("<input type=\"hidden\" name=\"action\" value=\"toggle-enabled\"/>")
                            .append("<input type=\"hidden\" name=\"username\" value=\"").append(escapeHtml(u.username)).append("\"/>")
                            .append("<button type=\"submit\" class=\"btn-sm btn-secondary\">").append(toggleLabel).append("</button>")
                            .append("</form>")
                            .append("<form class=\"user-action-form\" action=\"/users\" method=\"post\">")
                            .append("<input type=\"hidden\" name=\"action\" value=\"delete\"/>")
                            .append("<input type=\"hidden\" name=\"username\" value=\"").append(escapeHtml(u.username)).append("\"/>")
                            .append("<button type=\"submit\" class=\"btn-sm btn-danger\">Delete</button>")
                            .append("</form>");
                }
                actions.append("</div>");
                actionsCell = actions.toString();
            }

            String rowClass = isSelected ? "selected" : "";
            if (!u.enabled) {
                rowClass = (rowClass.isEmpty() ? "" : rowClass + " ") + "user-disabled";
            }

            tableRows.append("<tr").append(rowClass.isEmpty() ? "" : " class=\"" + rowClass + "\"").append(">")
                    .append("<td class=\"name-cell\">").append(fullName).append("</td>")
                    .append("<td class=\"email-cell\">").append(email).append("</td>");
            if (currentIsAdmin) {
                tableRows.append("<td class=\"status-cell\">").append(statusCell).append("</td>");
            }
            tableRows.append("<td class=\"role-cell\">").append(roleCell).append("</td>");
            if (currentIsAdmin) {
                tableRows.append("<td class=\"actions-cell\">").append(actionsCell).append("</td>");
            }
            tableRows.append("</tr>");
        }

        String statusHeader = currentIsAdmin ? "<th>Status</th>" : "";
        String actionsHeader = currentIsAdmin ? "<th>Actions</th>" : "";
        String addUserBar = currentIsAdmin
                ? "<div class=\"users-list-toolbar\"><a class=\"add-user-btn\" href=\"/add-user\">" +
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"14\" height=\"14\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M12 5v14M5 12h14\"></path></svg>" +
                "<span>Add User</span></a></div>"
                : "";
        String userListHtml = addUserBar +
                "<div class=\"user-search-bar\">" +
                "<input type=\"search\" id=\"userSearch\" placeholder=\"Search by name or email...\" aria-label=\"Search users by name or email\"/>" +
                "</div>" +
                "<div class=\"users-panel\"><table class=\"users-table\"><thead><tr>" +
                "<th>Name</th><th>Email</th>" + statusHeader + "<th>Role</th>" + actionsHeader +
                "</tr></thead><tbody>" + tableRows + "</tbody></table></div>";

        String editPanel = "";
        if (editData != null && !currentIsAdmin) {
            String fn = escapeHtml(editData.getOrDefault("firstName", ""));
            String ln = escapeHtml(editData.getOrDefault("lastName", ""));
            String em = escapeHtml(editData.getOrDefault("email", ""));
            String roleVal = editData.getOrDefault("role", editData.getOrDefault("isAdmin", "0"));
            String displayRole = formatRoleDisplay(roleVal);
            editPanel = "<div class=\"users-edit-panel read-only-details\"><h3>" + fn + " " + ln + "</h3>" +
                    "<p><strong>Email:</strong> " + em + "</p>" +
                    "<p><strong>Role:</strong> " + escapeHtml(displayRole) + "</p>" +
                    "<p class=\"read-only-note\">You do not have permission to edit user profiles.</p></div>";
        } else if (editData != null && currentIsAdmin) {
            String uname = escapeHtml(editData.getOrDefault("username", ""));
            String fn = escapeHtml(editData.getOrDefault("firstName", ""));
            String ln = escapeHtml(editData.getOrDefault("lastName", ""));
            String em = escapeHtml(editData.getOrDefault("email", ""));
            String roleVal = editData.getOrDefault("role", editData.getOrDefault("isAdmin", "0"));
            String roleSelected = roleVal == null || roleVal.isEmpty() ? "user" : roleVal;
            String roleControl = "<label for=\"role\">Role</label><select id=\"role\" name=\"role\">" +
                    buildRoleOptionsHtml(roleSelected, navOptions) + "</select>";
            String formFeedback = message == null ? "" : "<p class=\"form-feedback\">" + escapeHtml(message) + "</p>";

            editPanel = "<div class=\"users-edit-panel\"><form class=\"edit-form\" action=\"/users\" method=\"post\">" +
                    "<h3>Edit User</h3>" +
                    "<input type=\"hidden\" name=\"action\" value=\"update\"/>" +
                    "<input type=\"hidden\" name=\"username\" value=\"" + uname + "\"/>" +
                    "<label for=\"firstName\">First Name</label><input id=\"firstName\" name=\"firstName\" type=\"text\" value=\"" + fn + "\" required/>" +
                    "<label for=\"lastName\">Last Name</label><input id=\"lastName\" name=\"lastName\" type=\"text\" value=\"" + ln + "\" required/>" +
                    "<label for=\"email\">Email</label><input id=\"email\" name=\"email\" type=\"email\" value=\"" + em + "\" required/>" +
                    roleControl +
                    "<div class=\"form-actions\"><button type=\"submit\">Save Changes</button><a href=\"/users\">Cancel</a></div>" +
                    formFeedback +
                    "</form></div>";
        }

        String feedback = message == null || editData != null ? "" : "<div class=\"page-feedback\">" + escapeHtml(message) + "</div>";

        String headerHtml = "<div class=\"page-header\">" +
                "<div class=\"page-header-text\"><h2>Welcome, " + escapeHtml(currentFirstName) + "</h2>" +
                "<p>Signed in as " + escapeHtml(currentUsername) + "</p></div>" +
                "<div class=\"header-actions\">" +
                "<a class=\"header-btn header-btn-profile\" href=\"/profile\">Edit Profile</a>" +
                "<a class=\"header-btn header-btn-logout\" href=\"/logout\">Logout</a>" +
                "</div></div>";

        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>Users</title><style>" +
                "body{margin:0;font-family:Arial,Helvetica,sans-serif;background:linear-gradient(135deg,#0f172a,#2563eb);color:#f8fafc;}" +
                ".container{display:flex;min-height:100vh;}" +
                ".sidebar{width:260px;padding:1.5rem;background:rgba(255,255,255,0.04);border-right:1px solid rgba(255,255,255,0.04);}" +
                ".user-item{display:block;padding:0.6rem;border-radius:10px;color:#e6eef8;text-decoration:none;margin-bottom:0.35rem;}" +
                ".user-item:hover{background:rgba(255,255,255,0.03);}" +
                ".main{flex:1;padding:2rem;}" +
                ".page-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:1rem;gap:1rem;}" +
                ".page-header-text h2{margin:0 0 0.25rem;font-size:1.35rem;}" +
                ".page-header-text p{margin:0;opacity:0.9;font-size:0.9rem;}" +
                ".header-actions{display:flex;gap:0.65rem;align-items:center;flex-shrink:0;}" +
                ".header-btn{padding:0.55rem 1rem;border-radius:10px;font-size:0.85rem;font-weight:600;text-decoration:none;display:inline-flex;align-items:center;justify-content:center;transition:background 0.2s ease,transform 0.15s ease;}" +
                ".header-btn:hover{transform:translateY(-1px);}" +
                ".header-btn-profile{background:rgba(255,255,255,0.12);color:#f8fafc;border:1px solid rgba(255,255,255,0.2);}" +
                ".header-btn-profile:hover{background:rgba(255,255,255,0.2);color:#fff;}" +
                ".header-btn-logout{background:#dc2626;color:#fff;border:1px solid rgba(255,255,255,0.1);}" +
                ".header-btn-logout:hover{background:#b91c1c;color:#fff;}" +
                ".users-list-toolbar{display:flex;justify-content:flex-end;margin-bottom:0.65rem;}" +
                ".add-user-btn{padding:0.5rem 0.95rem;border-radius:10px;background:#2563eb;color:#fff;font-weight:600;text-decoration:none;font-size:0.82rem;display:inline-flex;align-items:center;gap:0.45rem;transition:background 0.2s ease,transform 0.15s ease;}" +
                ".add-user-btn:hover{background:#1d4ed8;transform:translateY(-1px);color:#fff;}" +
                ".users-content{width:100%;}" +
                ".users-edit-panel{margin-bottom:1rem;padding:1.25rem 1.5rem;border-radius:16px;background:rgba(255,255,255,0.06);box-shadow:0 12px 30px rgba(15,23,42,0.2);}" +
                ".users-edit-panel h3{margin:0 0 1rem;font-size:1.05rem;}" +
                ".read-only-note{opacity:0.75;margin-top:1rem;font-size:0.85rem;}" +
                ".form-feedback{color:#a5f3fc;font-weight:600;margin-top:0.5rem;}" +
                ".user-search-bar{margin-bottom:1rem;}" +
                ".user-search-bar input{width:100%;padding:0.65rem 0.85rem;border-radius:10px;border:1px solid rgba(255,255,255,0.12);background:rgba(255,255,255,0.06);color:#fff;font-size:0.85rem;box-sizing:border-box;}" +
                ".user-search-bar input::placeholder{color:rgba(248,250,252,0.65);}" +
                ".users-panel{border-radius:16px;overflow:hidden;background:rgba(255,255,255,0.06);box-shadow:0 12px 30px rgba(15,23,42,0.2);}" +
                ".users-table{width:100%;border-collapse:collapse;font-size:0.82rem;}" +
                ".users-table th,.users-table td{padding:0.55rem 0.7rem;text-align:left;border-bottom:1px solid rgba(255,255,255,0.08);vertical-align:middle;}" +
                ".users-table th{font-size:0.68rem;text-transform:uppercase;letter-spacing:0.06em;opacity:0.7;font-weight:600;}" +
                ".users-table tbody tr:hover{background:rgba(255,255,255,0.03);}" +
                ".users-table tr.selected{background:rgba(255,255,255,0.08);}" +
                ".users-table tr.user-disabled{opacity:0.65;}" +
                ".name-cell{font-weight:600;font-size:0.82rem;}" +
                ".email-cell{opacity:0.9;font-size:0.78rem;}" +
                ".status-badge{display:inline-block;padding:0.2rem 0.55rem;border-radius:999px;font-size:0.72rem;font-weight:600;}" +
                ".status-active{background:rgba(34,197,94,0.2);color:#86efac;}" +
                ".status-disabled{background:rgba(239,68,68,0.2);color:#fca5a5;}" +
                ".role-form{display:flex;gap:0.35rem;align-items:center;margin:0;}" +
                ".role-form select{font-size:0.78rem;padding:0.35rem 1.5rem 0.35rem 0.55rem;}" +
                ".user-actions{display:flex;flex-wrap:wrap;gap:0.35rem;align-items:center;}" +
                ".user-action-form{display:inline;margin:0;}" +
                ".page-feedback{padding:0.5rem 0;color:#a5f3fc;font-weight:600;}" +
                ".edit-form{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:0.75rem;}" +
                ".form-actions{display:flex;gap:0.75rem;align-items:center;margin-top:0.5rem;}" +
                "label{font-size:0.95rem;opacity:0.9;}" +
                "input{padding:0.8rem;border-radius:10px;border:1px solid rgba(255,255,255,0.12);background:rgba(255,255,255,0.06);color:#fff;}" +
                "select{padding:0.55rem 2rem 0.55rem 0.75rem;border-radius:10px;border:1px solid rgba(255,255,255,0.25);background:#1e293b;color:#f8fafc;font-size:0.82rem;cursor:pointer;appearance:auto;}" +
                "select option{background:#fff;color:#0f172a;}" +
                "select option:checked,select option:hover{background:#2563eb;color:#fff;}" +
                "select:disabled{opacity:0.85;cursor:not-allowed;background:rgba(255,255,255,0.08);}" +
                "button{padding:0.55rem 0.8rem;border-radius:8px;border:none;background:#2563eb;color:#fff;font-weight:600;cursor:pointer;}" +
                "button:hover{background:#1d4ed8;}" +
                ".btn-sm{padding:0.3rem 0.55rem;font-size:0.72rem;}" +
                ".btn-secondary{background:#475569;}" +
                ".btn-secondary:hover{background:#334155;}" +
                ".btn-danger{background:#dc2626;}" +
                ".btn-danger:hover{background:#b91c1c;}" +
                "a{color:#cbd5e1;text-decoration:none;}" +
                ".users-table a.button{padding:0.3rem 0.55rem;border-radius:8px;background:#2563eb;color:#fff;text-decoration:none;font-weight:600;display:inline-block;font-size:0.72rem;}" +
                ".users-table a.button:hover{background:#1d4ed8;}" +
                "</style></head><body>" +
                "<div class=\"container\">" + buildSidebarHtml(currentUsername, currentUserRole, navOptions, "user-item") + "<main class=\"main\">" +
                headerHtml + feedback +
                "<div class=\"users-content\">" + editPanel + userListHtml + "</div>" +
                "</main></div>" +
                "<script>" +
                "document.getElementById('userSearch').addEventListener('input',function(e){" +
                "const q=e.target.value.trim().toLowerCase();" +
                "document.querySelectorAll('.users-table tbody tr').forEach(function(row){" +
                "const name=(row.querySelector('.name-cell')?.textContent||'').toLowerCase();" +
                "const email=(row.querySelector('.email-cell')?.textContent||'').toLowerCase();" +
                "row.style.display=!q||name.includes(q)||email.includes(q)?'':'none';" +
                "});});" +
                "</script></body></html>";
    }

    // --- end users handlers ---

    private static void handleChangePassword(HttpExchange exchange) throws IOException {
        String username = getSessionUsername(exchange);
        if (username == null) {
            redirect(exchange, "/");
            return;
        }

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            handleChangePasswordPost(exchange, username);
            return;
        }

        sendHtmlResponse(exchange, 200, buildChangePasswordPage(null));
    }

    private static void handleChangePasswordPost(HttpExchange exchange, String username) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> formData = parseFormData(requestBody);

        String password = formData.getOrDefault("password", "").trim();
        String confirmPassword = formData.getOrDefault("confirmPassword", "").trim();

        if (password.isEmpty() || confirmPassword.isEmpty()) {
            sendHtmlResponse(exchange, 400, buildChangePasswordPage("Both password fields are required."));
            return;
        }
        if (!password.equals(confirmPassword)) {
            sendHtmlResponse(exchange, 400, buildChangePasswordPage("Passwords do not match. Please re-enter them."));
            return;
        }

        try {
            updatePasswordAndClearResetFlag(username, password);
            redirect(exchange, "/dashboard");
        } catch (SQLException ex) {
            sendHtmlResponse(exchange, 500, buildChangePasswordPage("Unable to update your password right now. Please try again later."));
        }
    }

    private static void updatePasswordAndClearResetFlag(String username, String password) throws SQLException {
        String sql = "UPDATE users SET password = ?, must_change_password = 0 WHERE username = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, password);
            statement.setString(2, username);
            statement.executeUpdate();
        }
    }

    private static void updateUserProfile(String username, String firstName, String lastName, String email, String password) throws SQLException {
        String sql;
        if (password.isEmpty()) {
            sql = "UPDATE users SET first_name = ?, last_name = ?, email = ? WHERE username = ?";
        } else {
            sql = "UPDATE users SET first_name = ?, last_name = ?, email = ?, password = ?, must_change_password = 0 WHERE username = ?";
        }

        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, firstName);
            statement.setString(2, lastName);
            statement.setString(3, email);
            if (password.isEmpty()) {
                statement.setString(4, username);
            } else {
                statement.setString(4, password);
                statement.setString(5, username);
            }
            statement.executeUpdate();
        }
    }

    private static Map<String, String> findUserProfile(String username) throws SQLException {
        String sql = "SELECT first_name, last_name, email FROM users WHERE username = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (var rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Map<String, String> profile = new LinkedHashMap<>();
                profile.put("first_name", rs.getString("first_name"));
                profile.put("last_name", rs.getString("last_name"));
                profile.put("email", rs.getString("email"));
                return profile;
            }
        }
    }

    private static String findFirstNameByCredentials(String username, String password) throws SQLException {
        String sql = "SELECT first_name FROM users WHERE username = ? AND password = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, password);
            try (var rs = statement.executeQuery()) {
                return rs.next() ? rs.getString("first_name") : null;
            }
        }
    }

    private static String findFirstNameByUsername(String username) throws SQLException {
        String sql = "SELECT first_name FROM users WHERE username = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (var rs = statement.executeQuery()) {
                return rs.next() ? rs.getString("first_name") : null;
            }
        }
    }

    private static String createSession(String username) {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new SessionInfo(username));
        return sessionId;
    }

    private static String getSessionIdFromCookie(HttpExchange exchange) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null) {
            return null;
        }
        for (String cookie : cookieHeader.split(";")) {
            String[] parts = cookie.trim().split("=", 2);
            if (parts.length == 2 && parts[0].equals(SESSION_COOKIE_NAME)) {
                return parts[1];
            }
        }
        return null;
    }

    private static String getSessionUsername(HttpExchange exchange) {
        String sessionId = getSessionIdFromCookie(exchange);
        if (sessionId == null) {
            return null;
        }
        SessionInfo session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        if (session.isExpired()) {
            sessions.remove(sessionId);
            exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE_NAME + "=deleted; Path=/; Max-Age=0; HttpOnly");
            return null;
        }
        session.touch();
        return session.username;
    }

    private static boolean userExists(String username) throws SQLException {
        String sql = "SELECT 1 FROM users WHERE username = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (var rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static UserRecord findUserByUsername(String username) throws SQLException {
        String sql = "SELECT first_name, is_admin, must_change_password, role, is_enabled FROM users WHERE username = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (var rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String role = rs.getString("role");
                boolean isAdmin = rs.getInt("is_admin") == 1;
                return new UserRecord(
                        rs.getString("first_name"),
                        isAdmin,
                        rs.getInt("must_change_password") == 1,
                        role != null && !role.isEmpty() ? role : (isAdmin ? "admin" : "user"),
                        rs.getInt("is_enabled") == 1
                );
            }
        }
    }

    private static UserRecord findUserByCredentials(String username, String password) throws SQLException {
        String sql = "SELECT first_name, is_admin, must_change_password, role, is_enabled FROM users WHERE username = ? AND password = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, password);
            try (var rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String role = rs.getString("role");
                boolean isAdmin = rs.getInt("is_admin") == 1;
                return new UserRecord(
                        rs.getString("first_name"),
                        isAdmin,
                        rs.getInt("must_change_password") == 1,
                        role != null && !role.isEmpty() ? role : (isAdmin ? "admin" : "user"),
                        rs.getInt("is_enabled") == 1
                );
            }
        }
    }

    private static class UserRecord {
        final String firstName;
        final boolean isAdmin;
        final boolean mustChangePassword;
        final String role;
        final boolean enabled;

        UserRecord(String firstName, boolean isAdmin, boolean mustChangePassword, String role, boolean enabled) {
            this.firstName = firstName;
            this.isAdmin = isAdmin;
            this.mustChangePassword = mustChangePassword;
            this.role = role;
            this.enabled = enabled;
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void handleRegistrationPost(HttpExchange exchange) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> formData = parseFormData(requestBody);

        String firstName = formData.getOrDefault("firstName", "").trim();
        String lastName = formData.getOrDefault("lastName", "").trim();
        String username = formData.getOrDefault("username", "").trim();
        String email = formData.getOrDefault("email", "").trim();
        String password = formData.getOrDefault("password", "").trim();

        if (firstName.isEmpty() || lastName.isEmpty() || username.isEmpty() || email.isEmpty() || password.isEmpty()) {
            sendHtmlResponse(exchange, 400, buildErrorPage("All fields are required. Please fill in every value."));
            return;
        }

        try {
            saveUser(firstName, lastName, username, email, password);
            sendHtmlResponse(exchange, 200, buildSuccessPage(firstName, username));
        } catch (SQLException ex) {
            String message = ex.getMessage();
            if (message != null && message.contains("UNIQUE")) {
                sendHtmlResponse(exchange, 400, buildErrorPage("A user with the same username or email already exists."));
            } else {
                sendHtmlResponse(exchange, 500, buildErrorPage("Could not save registration details. Please try again."));
            }
        }
    }

    private static void saveUser(String firstName, String lastName, String username, String email, String password) throws SQLException {
        saveUser(firstName, lastName, username, email, password, false, false);
    }

    private static void saveUser(String firstName, String lastName, String username, String email, String password, boolean isAdmin, boolean mustChangePassword) throws SQLException {
        String sql = "INSERT INTO users (first_name, last_name, username, email, password, is_admin, must_change_password, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, firstName);
            statement.setString(2, lastName);
            statement.setString(3, username);
            statement.setString(4, email);
            statement.setString(5, password);
            statement.setInt(6, isAdmin ? 1 : 0);
            statement.setInt(7, mustChangePassword ? 1 : 0);
            statement.setString(8, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private static Map<String, String> parseFormData(String body) {
        Map<String, String> result = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) {
            return result;
        }

        String[] pairs = body.split("&");
        for (String pair : pairs) {
            String[] parts = pair.split("=", 2);
            String key = urlDecode(parts[0]);
            String value = parts.length > 1 ? urlDecode(parts[1]) : "";
            result.put(key, value);
        }

        return result;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value.replace("+", "%20"), StandardCharsets.UTF_8);
    }

    private static void handlePage(HttpExchange exchange, Path html, String fileName) throws IOException {
        if (!Files.exists(html)) {
            String missing = fileName + " not found in working directory.";
            byte[] bytes = missing.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            return;
        }

        byte[] content = Files.readAllBytes(html);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(content);
        }
    }

    private static void sendHtmlResponse(HttpExchange exchange, int statusCode, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String buildSuccessPage(String firstName, String username) {
        return "<!DOCTYPE html>" +
                "<html lang=\"en\">" +
                "<head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>Registration Successful</title>" +
                "<style>body{margin:0;min-height:100vh;display:grid;place-items:center;font-family:Arial,Helvetica,sans-serif;background:linear-gradient(135deg,#0f172a,#2563eb);color:#f8fafc;}" +
                ".card{padding:3rem 2rem;border-radius:24px;background:rgba(255,255,255,0.14);box-shadow:0 20px 45px rgba(15,23,42,0.25);backdrop-filter:blur(10px);text-align:center;max-width:560px;margin:auto;}" +
                "a{display:inline-block;margin-top:1.5rem;color:#cbd5e1;text-decoration:none;font-weight:600;}a:hover{color:#ffffff;}" +
                "</style></head><body><main class=\"card\"><h1>Registration complete</h1>" +
                "<p>Thanks, " + escapeHtml(firstName) + ". Your account " + escapeHtml(username) + " is now saved.</p>" +
                "<a href=\"/\">Return to home</a></main></body></html>";
    }

    private static String buildDashboardPage(String firstName, String username, String userRole, java.util.List<NavOption> navOptions) {
        return "<!DOCTYPE html>" +
                "<html lang=\"en\">" +
                "<head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>Dashboard</title>" +
                "<style>" + sidebarLayoutStyles() + " .top-actions{display:flex;justify-content:flex-end;gap:0.75rem;margin-bottom:1.5rem;} .card{max-width:720px;padding:2rem;border-radius:20px;background:rgba(255,255,255,0.06);box-shadow:0 20px 45px rgba(15,23,42,0.25);backdrop-filter:blur(6px);} </style></head><body>" +
                "<div class=\"container\">" + buildSidebarHtml(username, userRole, navOptions, "nav-item") + "<main class=\"main\">" +
                "<div class=\"top-actions\"><a class=\"button\" href=\"/profile\">Edit Profile</a><a class=\"button\" href=\"/logout\">Logout</a></div>" +
                "<div class=\"card\"><h1 style=\"margin:0 0 0.5rem;\">Welcome, " + escapeHtml(firstName) + "</h1>" +
                "<p style=\"margin:0;opacity:0.9;\">Your username is " + escapeHtml(username) + ".</p>" +
                "</div></main></div></body></html>";
    }

    private static String buildAdminPanelPage(
            String username,
            String firstName,
            String labelValue,
            java.util.List<NavOption> navOptions,
            java.util.List<WorkItemDef> workItemDefs,
            java.util.List<StatusDef> statusDefs,
            String message,
            Integer editId,
            Integer editWorkItemId,
            Integer editStatusId
    ) {
        String feedback = message == null ? "" : "<p style=\"color:#a5f3fc;margin-top:1rem;font-weight:600;\">" + escapeHtml(message) + "</p>";
        StringBuilder existingOptions = new StringBuilder();
        if (navOptions.isEmpty()) {
            existingOptions.append("<p style=\"opacity:0.85;\">No venues yet.</p>");
        } else {
            existingOptions.append("<ul class=\"nav-option-list\">");
            for (NavOption option : navOptions) {
                if (editId != null && editId == option.id) {
                    existingOptions.append("<li class=\"nav-option-item\">")
                            .append("<form class=\"nav-option-edit-form\" action=\"/admin-panel\" method=\"post\">")
                            .append("<input type=\"hidden\" name=\"action\" value=\"update\"/>")
                            .append("<input type=\"hidden\" name=\"id\" value=\"").append(option.id).append("\"/>")
                            .append("<input name=\"label\" type=\"text\" value=\"").append(escapeHtml(option.label)).append("\" required/>")
                            .append("<button type=\"submit\" class=\"btn-sm\">Save</button>")
                            .append("<a class=\"btn-link\" href=\"/admin-panel\">Cancel</a>")
                            .append("</form></li>");
                } else {
                    existingOptions.append("<li class=\"nav-option-item\">")
                            .append("<span class=\"nav-option-label\">").append(escapeHtml(option.label)).append("</span>")
                            .append("<span class=\"nav-option-actions\">")
                            .append("<a class=\"button btn-sm\" href=\"/admin-panel?edit=").append(option.id).append("\">Edit</a>")
                            .append("<form class=\"nav-option-delete-form\" action=\"/admin-panel\" method=\"post\">")
                            .append("<input type=\"hidden\" name=\"action\" value=\"delete\"/>")
                            .append("<input type=\"hidden\" name=\"id\" value=\"").append(option.id).append("\"/>")
                            .append("<button type=\"submit\" class=\"btn-sm btn-danger\">Delete</button>")
                            .append("</form>")
                            .append("</span></li>");
                }
            }
            existingOptions.append("</ul>");
        }

        StringBuilder workItemsHtml = new StringBuilder();
        workItemsHtml.append("<ul class=\"nav-option-list\">");
        for (WorkItemDef item : workItemDefs) {
            if (editWorkItemId != null && editWorkItemId == item.id) {
                workItemsHtml.append("<li class=\"nav-option-item\">")
                        .append("<form class=\"nav-option-edit-form\" action=\"/admin-panel\" method=\"post\">")
                        .append("<input type=\"hidden\" name=\"action\" value=\"work-item-update\"/>")
                        .append("<input type=\"hidden\" name=\"id\" value=\"").append(item.id).append("\"/>")
                        .append("<input name=\"name\" type=\"text\" value=\"").append(escapeHtml(item.name)).append("\" required/>")
                        .append("<button type=\"submit\" class=\"btn-sm\">Save</button>")
                        .append("<a class=\"btn-link\" href=\"/admin-panel\">Cancel</a>")
                        .append("</form></li>");
            } else {
                workItemsHtml.append("<li class=\"nav-option-item\">")
                        .append("<span class=\"nav-option-label\">").append(escapeHtml(item.name)).append("</span>")
                        .append("<span class=\"nav-option-actions\">")
                        .append("<a class=\"button btn-sm\" href=\"/admin-panel?editWorkItem=").append(item.id).append("\">Edit</a>")
                        .append("<form class=\"nav-option-delete-form\" action=\"/admin-panel\" method=\"post\">")
                        .append("<input type=\"hidden\" name=\"action\" value=\"work-item-delete\"/>")
                        .append("<input type=\"hidden\" name=\"id\" value=\"").append(item.id).append("\"/>")
                        .append("<button type=\"submit\" class=\"btn-sm btn-danger\">Delete</button>")
                        .append("</form></span></li>");
            }
        }
        if (workItemDefs.isEmpty()) {
            workItemsHtml.append("<li class=\"nav-option-item\"><span style=\"opacity:0.85;\">No work items yet.</span></li>");
        }
        workItemsHtml.append("</ul>");

        StringBuilder statusHtml = new StringBuilder();
        statusHtml.append("<ul class=\"nav-option-list\">");
        for (StatusDef status : statusDefs) {
            if (editStatusId != null && editStatusId == status.id) {
                statusHtml.append("<li class=\"nav-option-item\">")
                        .append("<form class=\"nav-option-edit-form status-edit-form\" action=\"/admin-panel\" method=\"post\">")
                        .append("<input type=\"hidden\" name=\"action\" value=\"status-update\"/>")
                        .append("<input type=\"hidden\" name=\"id\" value=\"").append(status.id).append("\"/>")
                        .append("<input name=\"label\" type=\"text\" value=\"").append(escapeHtml(status.label)).append("\" required/>")
                        .append("<input name=\"percent\" type=\"number\" min=\"0\" max=\"100\" value=\"").append(status.percentValue).append("\" required/>")
                        .append("<button type=\"submit\" class=\"btn-sm\">Save</button>")
                        .append("<a class=\"btn-link\" href=\"/admin-panel\">Cancel</a>")
                        .append("</form></li>");
            } else {
                statusHtml.append("<li class=\"nav-option-item\">")
                        .append("<span class=\"nav-option-label\">").append(escapeHtml(status.label))
                        .append(" <span class=\"meta\">(").append(status.percentValue).append("%)</span></span>")
                        .append("<span class=\"nav-option-actions\">")
                        .append("<a class=\"button btn-sm\" href=\"/admin-panel?editStatus=").append(status.id).append("\">Edit</a>")
                        .append("<form class=\"nav-option-delete-form\" action=\"/admin-panel\" method=\"post\">")
                        .append("<input type=\"hidden\" name=\"action\" value=\"status-delete\"/>")
                        .append("<input type=\"hidden\" name=\"id\" value=\"").append(status.id).append("\"/>")
                        .append("<button type=\"submit\" class=\"btn-sm btn-danger\">Delete</button>")
                        .append("</form></span></li>");
            }
        }
        if (statusDefs.isEmpty()) {
            statusHtml.append("<li class=\"nav-option-item\"><span style=\"opacity:0.85;\">No status options yet.</span></li>");
        }
        statusHtml.append("</ul>");

        return "<!DOCTYPE html>" +
                "<html lang=\"en\">" +
                "<head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>Admin Panel</title>" +
                "<style>" + sidebarLayoutStyles() +
                " .top-actions{display:flex;justify-content:flex-end;gap:0.75rem;margin-bottom:1.5rem;}" +
                " .card{max-width:920px;padding:2rem;border-radius:20px;background:rgba(255,255,255,0.06);box-shadow:0 20px 45px rgba(15,23,42,0.25);backdrop-filter:blur(6px);margin-bottom:1.25rem;}" +
                " form{display:grid;gap:1rem;margin-top:1rem;}" +
                " label{font-size:0.95rem;opacity:0.9;}" +
                " input{padding:0.8rem;border-radius:10px;border:1px solid rgba(255,255,255,0.12);background:rgba(255,255,255,0.06);color:#fff;}" +
                " button{padding:0.7rem 1rem;border-radius:10px;border:none;background:#2563eb;color:#fff;font-weight:600;cursor:pointer;}" +
                " button:hover{background:#1d4ed8;}" +
                " .nav-option-list{list-style:none;margin:0;padding:0;}" +
                " .nav-option-item{display:flex;align-items:center;justify-content:space-between;gap:1rem;padding:0.75rem 0;border-bottom:1px solid rgba(255,255,255,0.08);}" +
                " .nav-option-item:last-child{border-bottom:none;}" +
                " .nav-option-label{font-weight:600;}" +
                " .nav-option-label .meta{opacity:0.75;font-weight:500;font-size:0.9rem;}" +
                " .nav-option-actions{display:flex;gap:0.5rem;align-items:center;}" +
                " .nav-option-edit-form,.nav-option-delete-form{display:flex;gap:0.5rem;align-items:center;margin:0;}" +
                " .nav-option-edit-form input{flex:1;min-width:0;}" +
                " .status-edit-form input[type=number]{max-width:110px;}" +
                " .inline-form{display:flex;gap:0.75rem;align-items:center;margin-top:1rem;}" +
                " .inline-form input{flex:1;}" +
                " .btn-sm{padding:0.45rem 0.75rem;font-size:0.85rem;}" +
                " .btn-danger{background:#dc2626;}" +
                " .btn-danger:hover{background:#b91c1c;}" +
                " .btn-link{color:#cbd5e1;text-decoration:none;font-size:0.9rem;}" +
                " .btn-link:hover{color:#fff;}" +
                "</style></head><body>" +
                "<div class=\"container\">" + buildSidebarHtml(username, "admin", navOptions, "nav-item") + "<main class=\"main\">" +
                "<div class=\"top-actions\"><a class=\"button\" href=\"/profile\">Edit Profile</a><a class=\"button\" href=\"/logout\">Logout</a></div>" +
                feedback +
                "<div class=\"card\"><h1 style=\"margin:0 0 0.5rem;\">Admin Panel</h1>" +
                "<p style=\"margin:0;opacity:0.9;\">Add a new venue to the left navigation. It will appear as a link for all logged-in users.</p>" +
                "<form action=\"/admin-panel\" method=\"post\">" +
                "<input type=\"hidden\" name=\"action\" value=\"add\"/>" +
                "<label for=\"label\">Venue Name</label>" +
                "<input id=\"label\" name=\"label\" type=\"text\" value=\"" + escapeHtml(labelValue) + "\" placeholder=\"Enter venue name\" required/>" +
                "<button type=\"submit\">Add Venue</button>" +
                "</form>" +
                "<div style=\"margin-top:2rem;\"><h2 style=\"margin:0 0 0.75rem;font-size:1.1rem;\">Venue</h2>" +
                existingOptions +
                "</div></div>" +
                "<div class=\"card\"><h2 style=\"margin:0 0 0.5rem;\">Work Items</h2>" +
                "<p style=\"margin:0;opacity:0.9;\">These items appear on every venue page. Add, rename, or remove them here.</p>" +
                "<form class=\"inline-form\" action=\"/admin-panel\" method=\"post\">" +
                "<input type=\"hidden\" name=\"action\" value=\"work-item-add\"/>" +
                "<input name=\"name\" type=\"text\" placeholder=\"New work item name\" required/>" +
                "<button type=\"submit\">Add Work Item</button>" +
                "</form>" +
                "<div style=\"margin-top:1.25rem;\">" + workItemsHtml + "</div></div>" +
                "<div class=\"card\"><h2 style=\"margin:0 0 0.5rem;\">Status Options</h2>" +
                "<p style=\"margin:0;opacity:0.9;\">These values appear in the status dropdown. Percent is used for overall progress.</p>" +
                "<form class=\"inline-form\" action=\"/admin-panel\" method=\"post\">" +
                "<input type=\"hidden\" name=\"action\" value=\"status-add\"/>" +
                "<input name=\"label\" type=\"text\" placeholder=\"New status label\" required/>" +
                "<input name=\"percent\" type=\"number\" min=\"0\" max=\"100\" value=\"0\" required/>" +
                "<button type=\"submit\">Add Status</button>" +
                "</form>" +
                "<div style=\"margin-top:1.25rem;\">" + statusHtml + "</div></div>" +
                "</main></div></body></html>";
    }

    private static String buildStatusPage(String username, String firstName, java.util.List<NavOption> navOptions, java.util.List<OptionProgress> progressList, OptionProgress selected) {
        StringBuilder overviewRows = new StringBuilder();
        if (progressList.isEmpty()) {
            overviewRows.append("<tr><td colspan=\"2\" class=\"empty-cell\">No venues available yet. Add venues in Admin Panel.</td></tr>");
        } else {
            for (OptionProgress progress : progressList) {
                boolean isSelected = selected != null && selected.option.id == progress.option.id;
                overviewRows.append("<tr").append(isSelected ? " class=\"selected\"" : "").append(">")
                        .append("<td><a class=\"option-link\" href=\"/status?id=").append(progress.option.id).append("\">")
                        .append(escapeHtml(progress.option.label)).append("</a></td>")
                        .append("<td><a class=\"progress-link\" href=\"/status?id=").append(progress.option.id).append("\">")
                        .append("<div class=\"progress-wrap\"><div class=\"progress-bar\" style=\"width:")
                        .append(progress.overallPercent).append("%;\"></div></div>")
                        .append("<span class=\"progress-label\">").append(progress.overallPercent).append("% overall</span>")
                        .append("</a></td></tr>");
            }
        }

        String detailHtml;
        if (selected == null) {
            detailHtml = "<div class=\"status-detail-empty\"><p>Select a venue to view individual progress.</p></div>";
        } else {
            StringBuilder detailRows = new StringBuilder();
            for (WorkItem item : selected.workItems) {
                int pct = statusToPercent(item.status);
                detailRows.append("<tr>")
                        .append("<td>").append(escapeHtml(item.name)).append("</td>")
                        .append("<td>").append(escapeHtml(item.status)).append("</td>")
                        .append("<td><div class=\"progress-wrap small\"><div class=\"progress-bar\" style=\"width:")
                        .append(pct).append("%;\"></div></div><span class=\"progress-label\">").append(pct).append("%</span></td>")
                        .append("</tr>");
            }
            detailHtml = "<div class=\"status-detail\">" +
                    "<div class=\"status-detail-header\">" +
                    "<h3>" + escapeHtml(selected.option.label) + "</h3>" +
                    "<p>Overall progress: <strong>" + selected.overallPercent + "%</strong></p>" +
                    "</div>" +
                    "<table class=\"status-table\"><thead><tr><th>Work Item</th><th>Status</th><th>Progress</th></tr></thead><tbody>" +
                    detailRows +
                    "</tbody></table></div>";
        }

        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>Status</title><style>" + sidebarLayoutStyles() +
                " .top-actions{display:flex;justify-content:flex-end;gap:0.75rem;margin-bottom:1.5rem;}" +
                " .status-layout{display:flex;gap:1.5rem;align-items:flex-start;}" +
                " .status-overview,.status-detail,.status-detail-empty{flex:1;min-width:0;padding:1.5rem;border-radius:16px;background:rgba(255,255,255,0.06);box-shadow:0 12px 30px rgba(15,23,42,0.2);}" +
                " .status-overview h2,.status-detail h3{margin:0 0 1rem;}" +
                " .status-detail-header p{margin:0 0 1rem;opacity:0.9;}" +
                " .status-table{width:100%;border-collapse:collapse;}" +
                " .status-table th,.status-table td{padding:0.8rem 0.7rem;text-align:left;border-bottom:1px solid rgba(255,255,255,0.08);vertical-align:middle;}" +
                " .status-table th{font-size:0.72rem;text-transform:uppercase;letter-spacing:0.06em;opacity:0.7;}" +
                " .status-table tr.selected{background:rgba(255,255,255,0.08);}" +
                " .option-link,.progress-link{color:#f8fafc;text-decoration:none;display:block;}" +
                " .option-link:hover,.progress-link:hover{color:#93c5fd;}" +
                " .progress-wrap{height:10px;border-radius:999px;background:rgba(255,255,255,0.12);overflow:hidden;margin-bottom:0.35rem;}" +
                " .progress-wrap.small{height:8px;display:inline-block;width:120px;margin-right:0.5rem;margin-bottom:0;vertical-align:middle;}" +
                " .progress-bar{height:100%;background:linear-gradient(90deg,#22c55e,#4ade80);}" +
                " .progress-label{font-size:0.85rem;opacity:0.9;}" +
                " .empty-cell,.status-detail-empty{opacity:0.8;}" +
                "</style></head><body>" +
                "<div class=\"container\">" + buildSidebarHtml(username, "admin", navOptions, "nav-item") + "<main class=\"main\">" +
                "<div class=\"top-actions\"><a class=\"button\" href=\"/status/export\">Export to PDF</a><a class=\"button\" href=\"/profile\">Edit Profile</a><a class=\"button\" href=\"/logout\">Logout</a></div>" +
                "<div class=\"status-layout\">" +
                "<div class=\"status-overview\"><h2>Overall Progress</h2>" +
                "<table class=\"status-table\"><thead><tr><th>Venue</th><th>Progress</th></tr></thead><tbody>" +
                overviewRows +
                "</tbody></table></div>" +
                detailHtml +
                "</div></main></div></body></html>";
    }

    private static String buildCustomPage(String firstName, String username, String userRole, NavOption option, java.util.List<NavOption> navOptions, java.util.List<WorkItem> workItems, java.util.List<StatusDef> statusDefs) {
        StringBuilder itemsHtml = new StringBuilder();
        itemsHtml.append("<div class=\"work-items\"><table class=\"work-table\"><thead><tr><th>Work Item</th><th>Status</th></tr></thead><tbody>");
        for (WorkItem item : workItems) {
            itemsHtml.append("<tr>")
                    .append("<td class=\"work-name\">").append(escapeHtml(item.name)).append("</td>")
                    .append("<td class=\"work-status\">")
                    .append("<form class=\"status-form\" action=\"/page?id=").append(option.id).append("\" method=\"post\">")
                    .append("<input type=\"hidden\" name=\"itemName\" value=\"").append(escapeHtml(item.name)).append("\"/>")
                    .append("<select name=\"status\" onchange=\"this.form.submit()\">");
            for (StatusDef status : statusDefs) {
                itemsHtml.append("<option value=\"").append(escapeHtml(status.label)).append("\"")
                        .append(status.label.equals(item.status) ? " selected" : "")
                        .append(">").append(escapeHtml(status.label)).append("</option>");
            }
            itemsHtml.append("</select></form></td></tr>");
        }
        itemsHtml.append("</tbody></table></div>");

        return "<!DOCTYPE html>" +
                "<html lang=\"en\">" +
                "<head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>" + escapeHtml(option.label) + "</title>" +
                "<style>" + sidebarLayoutStyles() +
                " .top-actions{display:flex;justify-content:flex-end;gap:0.75rem;margin-bottom:1.5rem;}" +
                " .card{max-width:900px;padding:2rem;border-radius:20px;background:rgba(255,255,255,0.06);box-shadow:0 20px 45px rgba(15,23,42,0.25);backdrop-filter:blur(6px);}" +
                " .work-items{margin-top:1.5rem;}" +
                " .work-table{width:100%;border-collapse:collapse;}" +
                " .work-table th,.work-table td{padding:0.85rem 0.75rem;text-align:left;border-bottom:1px solid rgba(255,255,255,0.08);vertical-align:middle;}" +
                " .work-table th{font-size:0.75rem;text-transform:uppercase;letter-spacing:0.06em;opacity:0.7;}" +
                " .work-name{font-weight:600;font-size:0.95rem;}" +
                " .status-form{margin:0;}" +
                " .status-form select{min-width:180px;padding:0.5rem 0.75rem;border-radius:10px;border:1px solid rgba(255,255,255,0.25);background:#1e293b;color:#f8fafc;font-size:0.9rem;}" +
                " .status-form select option{background:#fff;color:#0f172a;}" +
                "</style></head><body>" +
                "<div class=\"container\">" + buildSidebarHtml(username, userRole, navOptions, "nav-item") + "<main class=\"main\">" +
                "<div class=\"top-actions\"><a class=\"button\" href=\"/profile\">Edit Profile</a><a class=\"button\" href=\"/logout\">Logout</a></div>" +
                "<div class=\"card\"><h1 style=\"margin:0 0 0.25rem;\">Welcome to " + escapeHtml(option.label) + "</h1>" +
                "<p style=\"margin:0;opacity:0.85;\">Track progress for the work items below.</p>" +
                itemsHtml +
                "</div></main></div></body></html>";
    }

    private static String buildProfilePage(String username, String firstName, String lastName, String email, String message) {
        String feedback = message == null ? "" : "<p style=\"color:#a5f3fc;margin-top:1rem;font-weight:600;\">" + escapeHtml(message) + "</p>";
        return "<!DOCTYPE html>" +
                "<html lang=\"en\">" +
                "<head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>Edit Profile</title>" +
                "<style>body{margin:0;min-height:100vh;display:grid;place-items:center;font-family:Arial,Helvetica,sans-serif;background:linear-gradient(135deg,#0f172a,#2563eb);color:#f8fafc;}" +
                ".card{width:min(560px,calc(100%-2rem));padding:3rem;border-radius:24px;background:rgba(255,255,255,0.14);box-shadow:0 20px 45px rgba(15,23,42,0.25);backdrop-filter:blur(10px);}" +
                "form{display:grid;gap:1rem;}label{display:block;font-size:0.95rem;margin-bottom:0.25rem;opacity:0.9;}input{width:100%;padding:0.95rem 1rem;border-radius:14px;border:1px solid rgba(255,255,255,0.35);background:rgba(255,255,255,0.18);color:#f8fafc;font-size:1rem;outline:none;}input:focus{border-color:rgba(96,165,250,0.9);background:rgba(255,255,255,0.24);}button{padding:0.95rem 1rem;border:none;border-radius:14px;background:#2563eb;color:#ffffff;font-size:1rem;font-weight:600;cursor:pointer;transition:transform 0.2s ease,background 0.2s ease;}button:hover{background:#1d4ed8;transform:translateY(-1px);}a{display:inline-block;margin-top:1rem;color:#cbd5e1;text-decoration:none;font-size:0.95rem;}a:hover{color:#ffffff;}" +
                "</style></head><body><main class=\"card\"><h1>Edit Profile</h1><p>Update your user details below.</p>" +
                feedback +
                "<form action=\"/profile\" method=\"post\">" +
                "<div><label for=\"firstName\">First Name</label><input id=\"firstName\" name=\"firstName\" type=\"text\" value=\"" + escapeHtml(firstName) + "\" required></div>" +
                "<div><label for=\"lastName\">Last Name</label><input id=\"lastName\" name=\"lastName\" type=\"text\" value=\"" + escapeHtml(lastName) + "\" required></div>" +
                "<div><label for=\"email\">Email</label><input id=\"email\" name=\"email\" type=\"email\" value=\"" + escapeHtml(email) + "\" required></div>" +
                "<div><label for=\"password\">New Password</label><input id=\"password\" name=\"password\" type=\"password\" placeholder=\"Leave blank to keep current password\"></div>" +
                "<div><label>Username</label><input type=\"text\" value=\"" + escapeHtml(username) + "\" disabled></div>" +
                "<button type=\"submit\">Save Changes</button></form>" +
                "<a href=\"/dashboard\">Back to dashboard</a></main></body></html>";
    }

    private static String buildAddUserPage(String firstName, String lastName, String email, String message) {
        String feedback = message == null ? "" : "<p style=\"color:#a5f3fc;margin-top:1rem;font-weight:600;\">" + escapeHtml(message) + "</p>";
        return "<!DOCTYPE html>" +
                "<html lang=\"en\">" +
                "<head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>Add User</title>" +
                "<style>body{margin:0;min-height:100vh;display:grid;place-items:center;font-family:Arial,Helvetica,sans-serif;background:linear-gradient(135deg,#0f172a,#2563eb);color:#f8fafc;}" +
                ".card{width:min(560px,calc(100%-2rem));padding:3rem;border-radius:24px;background:rgba(255,255,255,0.14);box-shadow:0 20px 45px rgba(15,23,42,0.25);backdrop-filter:blur(10px);}" +
                "form{display:grid;gap:1rem;}label{display:block;font-size:0.95rem;margin-bottom:0.25rem;opacity:0.9;}input{width:100%;padding:0.95rem 1rem;border-radius:14px;border:1px solid rgba(255,255,255,0.35);background:rgba(255,255,255,0.18);color:#f8fafc;font-size:1rem;outline:none;}input:focus{border-color:rgba(96,165,250,0.9);background:rgba(255,255,255,0.24);}button{padding:0.95rem 1rem;border:none;border-radius:14px;background:#2563eb;color:#ffffff;font-size:1rem;font-weight:600;cursor:pointer;transition:transform 0.2s ease,background 0.2s ease;}button:hover{background:#1d4ed8;transform:translateY(-1px);}a{display:inline-block;margin-top:1rem;color:#cbd5e1;text-decoration:none;font-size:0.95rem;}a:hover{color:#ffffff;}" +
                "</style></head><body><main class=\"card\"><h1>Add New User</h1><p>New users will log in with their email as username. The default password is " + DEFAULT_NEW_USER_PASSWORD + ".</p>" +
                feedback +
                "<form action=\"/add-user\" method=\"post\">" +
                "<div><label for=\"firstName\">First Name</label><input id=\"firstName\" name=\"firstName\" type=\"text\" value=\"" + escapeHtml(firstName) + "\" required></div>" +
                "<div><label for=\"lastName\">Last Name</label><input id=\"lastName\" name=\"lastName\" type=\"text\" value=\"" + escapeHtml(lastName) + "\" required></div>" +
                "<div><label for=\"email\">Email</label><input id=\"email\" name=\"email\" type=\"email\" value=\"" + escapeHtml(email) + "\" required></div>" +
                "<button type=\"submit\">Create User</button></form>" +
                "<a href=\"/dashboard\">Back to dashboard</a></main></body></html>";
    }

    private static String buildChangePasswordPage(String message) {
        String feedback = message == null ? "" : "<p style=\"color:#fda4af;margin-top:1rem;font-weight:600;\">" + escapeHtml(message) + "</p>";
        return "<!DOCTYPE html>" +
                "<html lang=\"en\">" +
                "<head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>Change Password</title>" +
                "<style>body{margin:0;min-height:100vh;display:grid;place-items:center;font-family:Arial,Helvetica,sans-serif;background:linear-gradient(135deg,#0f172a,#2563eb);color:#f8fafc;}" +
                ".card{width:min(560px,calc(100%-2rem));padding:3rem;border-radius:24px;background:rgba(255,255,255,0.14);box-shadow:0 20px 45px rgba(15,23,42,0.25);backdrop-filter:blur(10px);}" +
                "form{display:grid;gap:1rem;}label{display:block;font-size:0.95rem;margin-bottom:0.25rem;opacity:0.9;}input{width:100%;padding:0.95rem 1rem;border-radius:14px;border:1px solid rgba(255,255,255,0.35);background:rgba(255,255,255,0.18);color:#f8fafc;font-size:1rem;outline:none;}input:focus{border-color:rgba(96,165,250,0.9);background:rgba(255,255,255,0.24);}button{padding:0.95rem 1rem;border:none;border-radius:14px;background:#2563eb;color:#ffffff;font-size:1rem;font-weight:600;cursor:pointer;transition:transform 0.2s ease,background 0.2s ease;}button:hover{background:#1d4ed8;transform:translateY(-1px);}a{display:inline-block;margin-top:1rem;color:#cbd5e1;text-decoration:none;font-size:0.95rem;}a:hover{color:#ffffff;}" +
                "</style></head><body><main class=\"card\"><h1>Change Password</h1><p>For security, please choose a new password before accessing your dashboard.</p>" +
                feedback +
                "<form action=\"/change-password\" method=\"post\">" +
                "<div><label for=\"password\">New Password</label><input id=\"password\" name=\"password\" type=\"password\" required></div>" +
                "<div><label for=\"confirmPassword\">Confirm Password</label><input id=\"confirmPassword\" name=\"confirmPassword\" type=\"password\" required></div>" +
                "<button type=\"submit\">Set Password</button></form></main></body></html>";
    }

    private static String buildLoginErrorPage(String title, String message) {
        String body = message == null || message.isEmpty()
                ? ""
                : "<p>" + escapeHtml(message) + "</p>";
        return "<!DOCTYPE html>" +
                "<html lang=\"en\">" +
                "<head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>" + escapeHtml(title) + "</title>" +
                "<style>body{margin:0;min-height:100vh;display:grid;place-items:center;font-family:Arial,Helvetica,sans-serif;background:linear-gradient(135deg,#0f172a,#2563eb);color:#f8fafc;}" +
                ".card{padding:3rem 2rem;border-radius:24px;background:rgba(255,255,255,0.14);box-shadow:0 20px 45px rgba(15,23,42,0.25);backdrop-filter:blur(10px);text-align:center;max-width:560px;margin:auto;}" +
                "a{display:inline-block;margin-top:1.5rem;color:#cbd5e1;text-decoration:none;font-weight:600;}a:hover{color:#ffffff;}" +
                "</style></head><body><main class=\"card\"><h1>" + escapeHtml(title) + "</h1>" +
                body +
                "<a href=\"/\">Back to login page</a></main></body></html>";
    }

    private static String buildErrorPage(String message) {
        return "<!DOCTYPE html>" +
                "<html lang=\"en\">" +
                "<head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>Registration Error</title>" +
                "<style>body{margin:0;min-height:100vh;display:grid;place-items:center;font-family:Arial,Helvetica,sans-serif;background:linear-gradient(135deg,#0f172a,#2563eb);color:#f8fafc;}" +
                ".card{padding:3rem 2rem;border-radius:24px;background:rgba(255,255,255,0.14);box-shadow:0 20px 45px rgba(15,23,42,0.25);backdrop-filter:blur(10px);text-align:center;max-width:560px;margin:auto;}" +
                "a{display:inline-block;margin-top:1.5rem;color:#cbd5e1;text-decoration:none;font-weight:600;}a:hover{color:#ffffff;}" +
                "</style></head><body><main class=\"card\"><h1>Registration failed</h1>" +
                "<p>" + escapeHtml(message) + "</p>" +
                "<a href=\"/register\">Back to registration</a></main></body></html>";
    }

    private static String escapeHtml(String input) {
        return input == null ? "" : input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
