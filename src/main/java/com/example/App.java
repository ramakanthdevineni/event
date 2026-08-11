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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class App {
    private static final Path INDEX_HTML = Path.of("index.html");
    private static final Path REGISTRATION_HTML = Path.of("registration.html");
    private static final Path DATA_DIR = Path.of("data");
    private static final String DB_URL = "jdbc:sqlite:data/users.db";
    private static final String STATUS_DB_URL = "jdbc:sqlite:data/status.db";
    private static final String SESSION_COOKIE_NAME = "SESSIONID";
    private static final String DEFAULT_NEW_USER_PASSWORD = "Match123$";
    private static final long SESSION_TIMEOUT_MS = 5 * 60 * 1000L;

    public static void main(String[] args) throws IOException {
        try {
            initDatabase();
        } catch (SQLException ex) {
            throw new IOException("Unable to initialize database", ex);
        }

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        String service = System.getenv().getOrDefault("SERVICE_NAME", "all").trim().toLowerCase(Locale.ROOT);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        registerServiceRoutes(server, service);
        server.setExecutor(null);

        System.out.println("Java service '" + service + "' started on http://localhost:" + port);
        server.start();
    }

    private static boolean serves(String service, String... names) {
        if ("all".equals(service)) {
            return true;
        }
        for (String name : names) {
            if (name.equals(service)) {
                return true;
            }
        }
        return false;
    }

    private static void registerServiceRoutes(HttpServer server, String service) {
        if (serves(service, "core")) {
            server.createContext("/register", App::handleRegister);
            server.createContext("/login", App::handleLogin);
            server.createContext("/dashboard", App::handleDashboard);
            server.createContext("/page", App::handleCustomPage);
            server.createContext("/change-password", App::handleChangePassword);
            server.createContext("/profile", App::handleProfile);
            server.createContext("/logout", App::handleLogout);
            server.createContext("/", App::handleHome);

            server.createContext("/api/login", App::apiLogin);
            server.createContext("/api/logout", App::apiLogout);
            server.createContext("/api/me", App::apiMe);
            server.createContext("/api/nav", App::apiNav);
            server.createContext("/api/profile", App::apiProfile);
            server.createContext("/api/change-password", App::apiChangePassword);
            server.createContext("/api/venues", App::apiVenues);
        }
        if (serves(service, "users")) {
            server.createContext("/users", App::handleUsers);
            server.createContext("/add-user", App::handleAddUser);
            server.createContext("/api/users", App::apiUsers);
        }
        if (serves(service, "admin")) {
            server.createContext("/admin-panel", App::handleAdminPanel);
            server.createContext("/api/admin", App::apiAdmin);
        }
        if (serves(service, "status")) {
            server.createContext("/status/export", App::handleStatusExport);
            server.createContext("/status", App::handleStatus);
            server.createContext("/api/status/export", App::apiStatusExport);
            server.createContext("/api/status", App::apiStatus);
        }
        if (serves(service, "mapview")) {
            server.createContext("/mapview", App::handleMapview);
            server.createContext("/api/mapview", App::apiMapview);
        }
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

        String service = System.getenv().getOrDefault("SERVICE_NAME", "all").trim().toLowerCase(Locale.ROOT);
        runWithDbRetry(() -> {
            try (Connection connection = openUsersDb();
                 Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
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
                // role column stores one or more roles as a comma-separated list (e.g. "user" or "Jeddah,Riyadh").
                // Existing single-role values remain valid after upgrade.
                addRoleColumnIfMissing(connection);
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS nav_options (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "label TEXT NOT NULL UNIQUE, " +
                        "created_at TEXT NOT NULL)"
                );
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS sessions (" +
                        "session_id TEXT PRIMARY KEY, " +
                        "username TEXT NOT NULL, " +
                        "last_activity_ms INTEGER NOT NULL)"
                );
            }
            initStatusDatabase();
        });

        // Only core (or monolithic "all") owns heavy seed/migration writes to avoid SQLite lock storms.
        if (serves(service, "core")) {
            runWithDbRetry(() -> {
                ensureDefaultWorkItemsForAllNavOptions();
                createDefaultAdminUser();
                try (Connection connection = openUsersDb();
                     Statement stmt = connection.createStatement()) {
                    stmt.executeUpdate("UPDATE users SET role = 'admin' WHERE is_admin = 1");
                } catch (SQLException ex) {
                    // ignore migration failures
                }
            });
        }
    }

    @FunctionalInterface
    private interface DbInitAction {
        void run() throws SQLException;
    }

    private static void runWithDbRetry(DbInitAction action) throws SQLException {
        SQLException last = null;
        for (int attempt = 1; attempt <= 12; attempt++) {
            try {
                action.run();
                return;
            } catch (SQLException ex) {
                last = ex;
                String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
                if (!msg.contains("busy") && !msg.contains("locked")) {
                    throw ex;
                }
                try {
                    Thread.sleep(250L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        throw last;
    }

    private static void initStatusDatabase() throws SQLException {
        try (Connection connection = openStatusDb();
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
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_nav_work_items_option ON nav_work_items(nav_option_id)");
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
            if (!hasAdminRole(user.role) && !hasUserRole(user.role)) {
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
            destroySession(sessionId);
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

    private static void handleMapview(HttpExchange exchange) throws IOException {
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
            java.util.List<OptionProgress> progressList = listOptionProgress();
            sendHtmlResponse(exchange, 200, buildMapviewPage(username, user.firstName, user.role, navOptions, progressList));
        } catch (SQLException ex) {
            sendHtmlResponse(exchange, 500, buildErrorPage("Unable to load map view right now."));
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

        Map<String, Integer> percentByStatus;
        try {
            percentByStatus = statusPercentMap();
        } catch (SQLException ex) {
            percentByStatus = Map.of();
        }

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
                int pct = statusToPercent(item.status, percentByStatus);
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
            if (!hasAdminRole(current.role) && !hasUserRole(current.role)) {
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
        List<String> selectedRoles = normalizeRoles(parseFormValues(body, "role"));
        form.put("role", serializeRoles(selectedRoles));
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
            handleUpdateUserRolePost(exchange, selectedRoles, username, currentUser);
            return;
        }

        String firstName = form.getOrDefault("firstName", "").trim();
        String lastName = form.getOrDefault("lastName", "").trim();
        String email = form.getOrDefault("email", "").trim();
        String role = serializeRoles(selectedRoles);

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
            if (!isValidRoles(selectedRoles, navOptions)) {
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
            destroySessionsForUser(username);
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
                destroySessionsForUser(username);
            }
            String statusLabel = newEnabled ? "enabled" : "disabled";
            redirectUsersNotice(exchange, "User \"" + target.firstName + " " + target.lastName + "\" " + statusLabel + ".");
        } catch (SQLException ex) {
            sendHtmlResponse(exchange, 500, buildErrorPage("Unable to update user status."));
        }
    }

    private static void handleUpdateUserRolePost(HttpExchange exchange, List<String> selectedRoles, String username, String currentUser) throws IOException {
        String role = serializeRoles(selectedRoles);

        try {
            var navOptions = listNavOptions();
            if (!isValidRoles(selectedRoles, navOptions)) {
                redirectUsersNotice(exchange, "Invalid role selected.");
                return;
            }

            UserEntry target = findUserEntryByUsername(username);
            if (target == null) {
                redirectUsersNotice(exchange, "User not found.");
                return;
            }

            updateUserRole(username, role);
            redirectUsersNotice(exchange, "Roles updated for \"" + target.firstName + " " + target.lastName + "\".");
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
        String sql = "SELECT id, label FROM nav_options ORDER BY label COLLATE NOCASE ASC";
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
        invalidateProgressCache();
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
        invalidateProgressCache();
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
        invalidateProgressCache();
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
        return listWorkItemsReadOnly(navOptionId, listWorkItemDefs());
    }

    private static java.util.List<WorkItem> listWorkItemsReadOnly(int navOptionId, java.util.List<WorkItemDef> defs) throws SQLException {
        String sql = "SELECT item_name, status FROM nav_work_items WHERE nav_option_id = ?";
        var byName = new LinkedHashMap<String, WorkItem>();
        try (Connection connection = openStatusDb();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, navOptionId);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    byName.put(rs.getString("item_name"), new WorkItem(rs.getString("item_name"), rs.getString("status")));
                }
            }
        }

        var ordered = new java.util.ArrayList<WorkItem>();
        for (WorkItemDef def : defs) {
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
        invalidateProgressCache();
    }

    private static Map<String, Integer> statusPercentMap() throws SQLException {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (StatusDef def : listStatusDefs()) {
            map.put(def.label, def.percentValue);
        }
        return map;
    }

    private static int statusToPercent(String status) {
        if (status == null) {
            return 0;
        }
        try {
            Integer pct = statusPercentMap().get(status);
            return pct != null ? pct : 0;
        } catch (SQLException ignored) {
            return 0;
        }
    }

    private static int statusToPercent(String status, Map<String, Integer> percentByStatus) {
        if (status == null || percentByStatus == null) {
            return 0;
        }
        Integer pct = percentByStatus.get(status);
        return pct != null ? pct : 0;
    }

    private static java.util.List<WorkItemDef> listWorkItemDefs() throws SQLException {
        long now = System.currentTimeMillis();
        java.util.List<WorkItemDef> cached = workItemDefsCache;
        if (cached != null && (now - workItemDefsCacheAtMs) < DEFS_CACHE_TTL_MS) {
            return cached;
        }
        synchronized (STATUS_DEFS_CACHE_LOCK) {
            cached = workItemDefsCache;
            now = System.currentTimeMillis();
            if (cached != null && (now - workItemDefsCacheAtMs) < DEFS_CACHE_TTL_MS) {
                return cached;
            }
            String sql = "SELECT id, name, sort_order FROM work_item_defs ORDER BY sort_order ASC, id ASC";
            var list = new java.util.ArrayList<WorkItemDef>();
            try (Connection connection = openStatusDb();
                 PreparedStatement stmt = connection.prepareStatement(sql);
                 var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new WorkItemDef(rs.getInt("id"), rs.getString("name"), rs.getInt("sort_order")));
                }
            }
            workItemDefsCache = java.util.List.copyOf(list);
            workItemDefsCacheAtMs = now;
            return workItemDefsCache;
        }
    }

    private static java.util.List<StatusDef> listStatusDefs() throws SQLException {
        long now = System.currentTimeMillis();
        java.util.List<StatusDef> cached = statusDefsCache;
        if (cached != null && (now - statusDefsCacheAtMs) < DEFS_CACHE_TTL_MS) {
            return cached;
        }
        synchronized (STATUS_DEFS_CACHE_LOCK) {
            cached = statusDefsCache;
            now = System.currentTimeMillis();
            if (cached != null && (now - statusDefsCacheAtMs) < DEFS_CACHE_TTL_MS) {
                return cached;
            }
            String sql = "SELECT id, label, percent_value, sort_order FROM status_defs ORDER BY sort_order ASC, id ASC";
            var list = new java.util.ArrayList<StatusDef>();
            try (Connection connection = openStatusDb();
                 PreparedStatement stmt = connection.prepareStatement(sql);
                 var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new StatusDef(rs.getInt("id"), rs.getString("label"), rs.getInt("percent_value"), rs.getInt("sort_order")));
                }
            }
            statusDefsCache = java.util.List.copyOf(list);
            statusDefsCacheAtMs = now;
            return statusDefsCache;
        }
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
        invalidateDefsCache();
    }

    private static void updateWorkItemDef(int id, String name) throws SQLException {
        String sql = "UPDATE work_item_defs SET name = ? WHERE id = ?";
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setInt(2, id);
            stmt.executeUpdate();
        }
        invalidateDefsCache();
    }

    private static void deleteWorkItemDef(int id) throws SQLException {
        String sql = "DELETE FROM work_item_defs WHERE id = ?";
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, id);
            stmt.executeUpdate();
        }
        invalidateDefsCache();
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
        invalidateDefsCache();
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
        invalidateDefsCache();
    }

    private static void deleteStatusDef(int id) throws SQLException {
        String sql = "DELETE FROM status_defs WHERE id = ?";
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, id);
            stmt.executeUpdate();
        }
        invalidateDefsCache();
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
        invalidateProgressCache();
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
        invalidateProgressCache();
    }

    private static void deleteWorkItemAcrossOptions(String itemName) throws SQLException {
        String sql = "DELETE FROM nav_work_items WHERE item_name = ?";
        try (Connection connection = DriverManager.getConnection(STATUS_DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, itemName);
            stmt.executeUpdate();
        }
        invalidateProgressCache();
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
        invalidateProgressCache();
    }

    private static int calculateOverallProgress(java.util.List<WorkItem> workItems) {
        try {
            return calculateOverallProgress(workItems, statusPercentMap());
        } catch (SQLException ex) {
            return 0;
        }
    }

    private static int calculateOverallProgress(java.util.List<WorkItem> workItems, Map<String, Integer> percentByStatus) {
        if (workItems == null || workItems.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (WorkItem item : workItems) {
            total += statusToPercent(item.status, percentByStatus);
        }
        return Math.round(total / (float) workItems.size());
    }

    private static final Object PROGRESS_CACHE_LOCK = new Object();
    private static volatile java.util.List<OptionProgress> progressCache;
    private static volatile long progressCacheAtMs;
    private static final long PROGRESS_CACHE_TTL_MS = 3_000L;

    private static final Object STATUS_DEFS_CACHE_LOCK = new Object();
    private static volatile java.util.List<StatusDef> statusDefsCache;
    private static volatile long statusDefsCacheAtMs;
    private static volatile java.util.List<WorkItemDef> workItemDefsCache;
    private static volatile long workItemDefsCacheAtMs;
    // Short TTL so admin/core/status/mapview processes pick up shared DB changes without restart.
    private static final long DEFS_CACHE_TTL_MS = 1_000L;

    private static void invalidateProgressCache() {
        synchronized (PROGRESS_CACHE_LOCK) {
            progressCache = null;
            progressCacheAtMs = 0L;
        }
    }

    private static void invalidateDefsCache() {
        synchronized (STATUS_DEFS_CACHE_LOCK) {
            statusDefsCache = null;
            statusDefsCacheAtMs = 0L;
            workItemDefsCache = null;
            workItemDefsCacheAtMs = 0L;
        }
        invalidateProgressCache();
    }

    /**
     * Fast read path for Status/Mapview/PDF: one query for all work items, no seeding writes.
     * Seeding is handled at core startup and when venues/work items are edited in Admin Panel.
     */
    private static java.util.List<OptionProgress> listOptionProgress() throws SQLException {
        long now = System.currentTimeMillis();
        java.util.List<OptionProgress> cached = progressCache;
        if (cached != null && (now - progressCacheAtMs) < PROGRESS_CACHE_TTL_MS) {
            return cached;
        }
        synchronized (PROGRESS_CACHE_LOCK) {
            cached = progressCache;
            now = System.currentTimeMillis();
            if (cached != null && (now - progressCacheAtMs) < PROGRESS_CACHE_TTL_MS) {
                return cached;
            }

            java.util.List<NavOption> options = listNavOptions();
            java.util.List<WorkItemDef> defs = listWorkItemDefs();
            Map<String, Integer> percentByStatus = statusPercentMap();

            Map<Integer, Map<String, WorkItem>> itemsByOption = new LinkedHashMap<>();
            try (Connection connection = openStatusDb();
                 PreparedStatement stmt = connection.prepareStatement(
                         "SELECT nav_option_id, item_name, status FROM nav_work_items");
                 var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int optionId = rs.getInt("nav_option_id");
                    itemsByOption
                            .computeIfAbsent(optionId, id -> new LinkedHashMap<>())
                            .put(rs.getString("item_name"), new WorkItem(rs.getString("item_name"), rs.getString("status")));
                }
            }

            var result = new java.util.ArrayList<OptionProgress>();
            for (NavOption option : options) {
                Map<String, WorkItem> byName = itemsByOption.getOrDefault(option.id, Map.of());
                var ordered = new java.util.ArrayList<WorkItem>();
                for (WorkItemDef def : defs) {
                    WorkItem item = byName.get(def.name);
                    if (item != null) {
                        ordered.add(item);
                    }
                }
                result.add(new OptionProgress(option, ordered, calculateOverallProgress(ordered, percentByStatus)));
            }
            progressCache = java.util.List.copyOf(result);
            progressCacheAtMs = System.currentTimeMillis();
            return progressCache;
        }
    }

    private static void updateUsersRoleByLabel(String oldLabel, String newLabel) throws SQLException {
        List<String[]> updates = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement select = connection.prepareStatement("SELECT username, role FROM users");
             var rs = select.executeQuery()) {
            while (rs.next()) {
                List<String> roles = parseRoles(rs.getString("role"));
                boolean changed = false;
                for (int i = 0; i < roles.size(); i++) {
                    if (roles.get(i).equalsIgnoreCase(oldLabel)) {
                        roles.set(i, newLabel);
                        changed = true;
                    }
                }
                if (changed) {
                    String serialized = serializeRoles(roles);
                    updates.add(new String[]{rs.getString("username"), serialized, hasAdminRole(serialized) ? "1" : "0"});
                }
            }
        }
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement update = connection.prepareStatement("UPDATE users SET role = ?, is_admin = ? WHERE username = ?")) {
            for (String[] row : updates) {
                update.setString(1, row[1]);
                update.setInt(2, "1".equals(row[2]) ? 1 : 0);
                update.setString(3, row[0]);
                update.executeUpdate();
            }
        }
    }

    private static void resetUsersRoleByLabel(String label) throws SQLException {
        List<String[]> updates = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement select = connection.prepareStatement("SELECT username, role FROM users");
             var rs = select.executeQuery()) {
            while (rs.next()) {
                List<String> roles = parseRoles(rs.getString("role"));
                List<String> filtered = new ArrayList<>();
                boolean changed = false;
                for (String role : roles) {
                    if (role.equalsIgnoreCase(label)) {
                        changed = true;
                    } else {
                        filtered.add(role);
                    }
                }
                if (changed) {
                    String serialized = serializeRoles(filtered);
                    updates.add(new String[]{rs.getString("username"), serialized, hasAdminRole(serialized) ? "1" : "0"});
                }
            }
        }
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement update = connection.prepareStatement("UPDATE users SET role = ?, is_admin = ? WHERE username = ?")) {
            for (String[] row : updates) {
                update.setString(1, row[1]);
                update.setInt(2, "1".equals(row[2]) ? 1 : 0);
                update.setString(3, row[0]);
                update.executeUpdate();
            }
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
        // Admin => full nav. "user" alone => dashboard/users/mapview. Venue roles may be combined
        // (and optionally with "user") to grant access to each matching venue page.
        if (hasAdminRole(userRole)) {
            nav.append("<a class=\"").append(navItemClass).append("\" href=\"/dashboard\">Dashboard</a>");
            nav.append("<a class=\"").append(navItemClass).append("\" href=\"/users\">Users</a>");
            nav.append("<a class=\"").append(navItemClass).append("\" href=\"/admin-panel\">Admin Panel</a>");
            nav.append("<a class=\"").append(navItemClass).append("\" href=\"/status\">Status</a>");
            nav.append("<a class=\"").append(navItemClass).append("\" href=\"/mapview\">Mapview</a>");
            for (NavOption option : navOptions) {
                nav.append("<a class=\"").append(navItemClass).append("\" href=\"/page?id=").append(option.id).append("\">")
                        .append(escapeHtml(option.label)).append("</a>");
            }
        } else {
            List<NavOption> venueMatches = matchedVenueOptions(userRole, navOptions);
            boolean standardAccess = hasUserRole(userRole) || venueMatches.isEmpty();
            if (standardAccess) {
                nav.append("<a class=\"").append(navItemClass).append("\" href=\"/dashboard\">Dashboard</a>");
                nav.append("<a class=\"").append(navItemClass).append("\" href=\"/users\">Users</a>");
                nav.append("<a class=\"").append(navItemClass).append("\" href=\"/mapview\">Mapview</a>");
            } else {
                nav.append("<a class=\"").append(navItemClass).append("\" href=\"/mapview\">Mapview</a>");
            }
            for (NavOption matched : venueMatches) {
                nav.append("<a class=\"").append(navItemClass).append("\" href=\"/page?id=").append(matched.id).append("\">")
                        .append(escapeHtml(matched.label)).append("</a>");
            }
        }
        return "<aside class=\"sidebar\" id=\"app-sidebar\">" +
                "<button type=\"button\" class=\"nav-menu-toggle\" aria-expanded=\"false\" aria-controls=\"sidebar-nav-panel\">" +
                "<span class=\"nav-menu-icon\" aria-hidden=\"true\">&#9776;</span><span>Menu</span></button>" +
                "<div class=\"sidebar-panel\" id=\"sidebar-nav-panel\">" +
                "<h2 class=\"sidebar-title\">Navigation</h2><nav>" + nav + "</nav><hr/>" +
                "<p class=\"sidebar-user\">Logged in as " + escapeHtml(username) + "</p>" +
                "</div></aside>" +
                "<script>(function(){var side=document.getElementById('app-sidebar');" +
                "var btn=side&&side.querySelector('.nav-menu-toggle');" +
                "if(!side||!btn){return;}" +
                "btn.addEventListener('click',function(e){e.stopPropagation();" +
                "var open=side.classList.toggle('nav-open');" +
                "btn.setAttribute('aria-expanded',open?'true':'false');});" +
                "document.addEventListener('click',function(e){" +
                "if(!side.contains(e.target)){side.classList.remove('nav-open');" +
                "btn.setAttribute('aria-expanded','false');}});" +
                "})();</script>";
    }

    // Roles are stored comma-separated in users.role. Admin grants full access; venue labels
    // grant those custom pages; "user" alone keeps dashboard/users/mapview access.
    private static List<String> parseRoles(String rolesCsv) {
        List<String> roles = new ArrayList<>();
        if (rolesCsv == null || rolesCsv.isBlank()) {
            return roles;
        }
        for (String part : rolesCsv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                roles.add(trimmed);
            }
        }
        return roles;
    }

    private static List<String> normalizeRoles(List<String> roles) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (roles != null) {
            for (String role : roles) {
                if (role == null) {
                    continue;
                }
                String trimmed = role.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String key = trimmed.toLowerCase(Locale.ROOT);
                boolean exists = false;
                for (String existing : unique) {
                    if (existing.equalsIgnoreCase(trimmed)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    if ("admin".equals(key)) {
                        unique.add("admin");
                    } else if ("user".equals(key)) {
                        unique.add("user");
                    } else {
                        unique.add(trimmed);
                    }
                }
            }
        }
        if (unique.isEmpty()) {
            unique.add("user");
        }
        return new ArrayList<>(unique);
    }

    private static String serializeRoles(List<String> roles) {
        return String.join(",", normalizeRoles(roles));
    }

    private static boolean roleListContains(List<String> roles, String target) {
        if (target == null) {
            return false;
        }
        for (String role : roles) {
            if (target.equalsIgnoreCase(role)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAdminRole(String rolesCsv) {
        return roleListContains(parseRoles(rolesCsv), "admin");
    }

    private static boolean hasUserRole(String rolesCsv) {
        List<String> roles = parseRoles(rolesCsv);
        if (roles.isEmpty()) {
            return true;
        }
        return roleListContains(roles, "user");
    }

    private static boolean isAdminRole(String role) {
        return hasAdminRole(role);
    }

    private static boolean isStandardUserRole(String role) {
        List<String> roles = parseRoles(role);
        if (roles.isEmpty()) {
            return true;
        }
        if (hasAdminRole(role)) {
            return false;
        }
        for (String r : roles) {
            if (!"user".equalsIgnoreCase(r)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidRole(String role, java.util.List<NavOption> navOptions) {
        if (role == null || role.isBlank()) {
            return false;
        }
        if ("admin".equalsIgnoreCase(role) || "user".equalsIgnoreCase(role)) {
            return true;
        }
        return findNavOptionByLabel(role, navOptions) != null;
    }

    private static boolean isValidRoles(List<String> roles, java.util.List<NavOption> navOptions) {
        List<String> normalized = normalizeRoles(roles);
        for (String role : normalized) {
            if (!isValidRole(role, navOptions)) {
                return false;
            }
        }
        return true;
    }

    private static List<NavOption> matchedVenueOptions(String rolesCsv, java.util.List<NavOption> navOptions) {
        List<NavOption> matched = new ArrayList<>();
        LinkedHashSet<Integer> seen = new LinkedHashSet<>();
        for (String role : parseRoles(rolesCsv)) {
            if ("admin".equalsIgnoreCase(role) || "user".equalsIgnoreCase(role)) {
                continue;
            }
            NavOption option = findNavOptionByLabel(role, navOptions);
            if (option != null && seen.add(option.id)) {
                matched.add(option);
            }
        }
        matched.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label));
        return matched;
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
        if (hasAdminRole(user.role)) {
            return true;
        }
        for (String role : parseRoles(user.role)) {
            if (option.label.equalsIgnoreCase(role)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveHomePath(UserRecord user, java.util.List<NavOption> navOptions) {
        if (hasAdminRole(user.role) || roleListContains(parseRoles(user.role), "user") || isStandardUserRole(user.role)) {
            return "/dashboard";
        }
        List<NavOption> venues = matchedVenueOptions(user.role, navOptions);
        return venues.isEmpty() ? "/dashboard" : "/page?id=" + venues.get(0).id;
    }

    private static String formatRoleDisplay(String role) {
        List<String> roles = normalizeRoles(parseRoles(role));
        List<String> system = new ArrayList<>();
        List<String> venues = new ArrayList<>();
        for (String r : roles) {
            if ("user".equalsIgnoreCase(r)) {
                system.add("User");
            } else if ("admin".equalsIgnoreCase(r)) {
                system.add("Admin");
            } else {
                venues.add(r);
            }
        }
        venues.sort(String.CASE_INSENSITIVE_ORDER);
        List<String> labels = new ArrayList<>(system);
        labels.addAll(venues);
        return String.join(", ", labels);
    }

    private static String buildRoleCheckboxesHtml(String selectedRolesCsv, java.util.List<NavOption> navOptions, String idPrefix) {
        List<String> selected = normalizeRoles(parseRoles(selectedRolesCsv));
        String safePrefix = escapeHtml(idPrefix);
        String summary = selected.isEmpty() ? "Select roles..." : formatRoleDisplay(serializeRoles(selected));

        StringBuilder options = new StringBuilder();
        options.append(roleCheckbox("user", "User", selected, idPrefix));
        options.append(roleCheckbox("admin", "Admin", selected, idPrefix));
        for (NavOption option : navOptions) {
            options.append(roleCheckbox(option.label, option.label, selected, idPrefix));
        }

        return "<div class=\"role-dropdown\" data-role-dropdown>" +
                "<button type=\"button\" class=\"role-dropdown-toggle\" aria-expanded=\"false\">" +
                "<span class=\"role-dropdown-summary\">" + escapeHtml(summary) + "</span>" +
                "<span class=\"role-dropdown-caret\">&#9662;</span>" +
                "</button>" +
                "<div class=\"role-dropdown-panel\" hidden>" +
                "<input type=\"search\" class=\"role-dropdown-search\" placeholder=\"Search roles...\" " +
                "aria-label=\"Search roles\" autocomplete=\"off\"/>" +
                "<div class=\"role-checks\" id=\"" + safePrefix + "-list\">" + options + "</div>" +
                "</div></div>";
    }

    private static String roleCheckbox(String value, String label, List<String> selectedRoles, String idPrefix) {
        boolean checked = roleListContains(selectedRoles, value);
        String id = escapeHtml(idPrefix + "-" + value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
        return "<label class=\"role-check\" for=\"" + id + "\" data-role-label=\"" + escapeHtml(label.toLowerCase(Locale.ROOT)) + "\">" +
                "<input type=\"checkbox\" id=\"" + id + "\" name=\"role\" value=\"" + escapeHtml(value) + "\"" +
                (checked ? " checked" : "") + "/>" +
                "<span>" + escapeHtml(label) + "</span></label>";
    }

    private static String sidebarLayoutStyles() {
        return "html,body{margin:0;padding:0;max-width:100%;overflow-x:hidden;}" +
                "body{font-family:Arial,Helvetica,sans-serif;background:linear-gradient(135deg,#0f172a,#2563eb);color:#f8fafc;}" +
                "*,*:before,*:after{box-sizing:border-box;}" +
                ".container{display:flex;min-height:100vh;width:100%;max-width:100%;}" +
                ".sidebar{width:260px;flex-shrink:0;padding:1.5rem;background:rgba(255,255,255,0.04);border-right:1px solid rgba(255,255,255,0.04);}" +
                ".nav-menu-toggle{display:none;align-items:center;gap:0.45rem;padding:0.55rem 0.85rem;border:1px solid rgba(255,255,255,0.22);" +
                "border-radius:10px;background:#1e293b;color:#f8fafc;font-size:0.9rem;font-weight:600;cursor:pointer;}" +
                ".nav-menu-toggle:hover{background:#334155;}" +
                ".nav-menu-icon{font-size:1.1rem;line-height:1;}" +
                ".sidebar-title{margin:0 0 0.75rem;font-size:1.25rem;}" +
                ".sidebar-user{opacity:0.8;font-size:0.9rem;margin:0;}" +
                ".nav-item,.user-item{display:block;padding:0.6rem;border-radius:10px;color:#e6eef8;text-decoration:none;margin-bottom:0.35rem;}" +
                ".nav-item:hover,.user-item:hover{background:rgba(255,255,255,0.03);}" +
                ".main{flex:1;min-width:0;padding:2rem;width:100%;}" +
                "a.button{padding:0.6rem 0.9rem;border-radius:10px;background:#2563eb;color:#fff;text-decoration:none;font-weight:600;display:inline-block;}" +
                "a.button:hover{background:#1d4ed8;}" +
                "@media (max-width:900px){" +
                ".container{flex-direction:column;}" +
                ".sidebar{width:100%;padding:calc(0.75rem + max(env(safe-area-inset-top, 0px), 36px)) 0.85rem 0.75rem;" +
                "border-right:none;border-bottom:1px solid rgba(255,255,255,0.08);" +
                "position:sticky;top:0;z-index:40;background:rgba(15,23,42,0.96);backdrop-filter:blur(8px);}" +
                ".nav-menu-toggle{display:inline-flex;}" +
                ".sidebar-panel{display:none;margin-top:0.65rem;padding-top:0.35rem;}" +
                ".sidebar.nav-open .sidebar-panel{display:block;}" +
                ".sidebar-title{font-size:1.05rem;margin-bottom:0.5rem;}" +
                ".main{padding:1rem;width:100%;max-width:100%;}" +
                ".top-actions{justify-content:flex-start;flex-wrap:wrap;gap:0.5rem;margin-bottom:1rem;}" +
                ".card,.status-overview,.status-detail,.status-detail-empty,.map-card,.legend-card," +
                ".users-panel,.users-edit-panel{max-width:100%;width:100%;padding:1.15rem;}" +
                ".status-layout,.map-layout{flex-direction:column;}" +
                ".work-table,.status-table,.legend-table,.users-table{display:block;width:100%;overflow-x:auto;-webkit-overflow-scrolling:touch;}" +
                ".status-form select{min-width:0;width:100%;max-width:100%;}" +
                ".page-header{flex-direction:column;align-items:flex-start;}" +
                ".header-actions{width:100%;flex-wrap:wrap;}" +
                "}";
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
        String serialized = serializeRoles(parseRoles(role));
        boolean isAdmin = hasAdminRole(serialized);
        String sql = "UPDATE users SET is_admin = ?, role = ? WHERE username = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, isAdmin ? 1 : 0);
            stmt.setString(2, serialized);
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
        String serialized = serializeRoles(parseRoles(role));
        boolean isAdmin = hasAdminRole(serialized);
        String sql = "UPDATE users SET first_name = ?, last_name = ?, email = ?, is_admin = ?, role = ? WHERE username = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, firstName);
            stmt.setString(2, lastName);
            stmt.setString(3, email);
            stmt.setInt(4, isAdmin ? 1 : 0);
            stmt.setString(5, serialized);
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
                String roleIdPrefix = "role-" + u.username.hashCode();
                roleCell = "<form class=\"role-form\" action=\"/users\" method=\"post\">" +
                        "<input type=\"hidden\" name=\"action\" value=\"update-role\"/>" +
                        "<input type=\"hidden\" name=\"username\" value=\"" + escapeHtml(u.username) + "\"/>" +
                        "<div class=\"role-controls\">" +
                        buildRoleCheckboxesHtml(roleSelected, navOptions, roleIdPrefix) +
                        "<button type=\"submit\" class=\"btn-sm role-save-btn\" title=\"Save roles\">Save</button>" +
                        "</div></form>";
            } else {
                roleCell = "<span class=\"role-display\">" + escapeHtml(formatRoleDisplay(roleSelected)) + "</span>";
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
            String roleControl = "<div class=\"role-field\"><span class=\"role-label\">Roles</span>" +
                    buildRoleCheckboxesHtml(roleSelected, navOptions, "edit-role") + "</div>";
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
                "<title>Users</title><style>" + sidebarLayoutStyles() +
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
                ".users-content{width:100%;min-width:0;}" +
                ".users-edit-panel{margin-bottom:1rem;padding:1.25rem 1.5rem;border-radius:16px;background:rgba(255,255,255,0.06);box-shadow:0 12px 30px rgba(15,23,42,0.2);}" +
                ".users-edit-panel h3{margin:0 0 1rem;font-size:1.05rem;}" +
                ".read-only-note{opacity:0.75;margin-top:1rem;font-size:0.85rem;}" +
                ".form-feedback{color:#a5f3fc;font-weight:600;margin-top:0.5rem;}" +
                ".user-search-bar{margin-bottom:0.75rem;}" +
                ".user-search-bar input{width:100%;padding:0.65rem 0.85rem;border-radius:10px;border:1px solid rgba(255,255,255,0.12);background:rgba(255,255,255,0.06);color:#fff;font-size:0.85rem;box-sizing:border-box;}" +
                ".user-search-bar input::placeholder{color:rgba(248,250,252,0.65);}" +
                ".users-panel{border-radius:16px;background:rgba(255,255,255,0.06);box-shadow:0 12px 30px rgba(15,23,42,0.2);overflow:auto;max-height:calc(100vh - 220px);}" +
                ".users-table{width:100%;border-collapse:separate;border-spacing:0;font-size:0.8rem;table-layout:fixed;}" +
                ".users-table th,.users-table td{padding:0.45rem 0.65rem;text-align:left;border-bottom:1px solid rgba(255,255,255,0.08);vertical-align:middle;}" +
                ".users-table th{position:sticky;top:0;z-index:5;background:#1e3a8a;font-size:0.68rem;text-transform:uppercase;letter-spacing:0.06em;opacity:1;font-weight:600;}" +
                ".users-table tbody tr:hover{background:rgba(255,255,255,0.03);}" +
                ".users-table tr.selected{background:rgba(255,255,255,0.08);}" +
                ".users-table tr.user-disabled{opacity:0.65;}" +
                ".users-table th:nth-child(1),.users-table td.name-cell{width:18%;}" +
                ".users-table th:nth-child(2),.users-table td.email-cell{width:22%;}" +
                ".users-table th:nth-child(3),.users-table td.status-cell{width:10%;}" +
                ".users-table th:nth-child(4),.users-table td.role-cell{width:28%;}" +
                ".users-table th:nth-child(5),.users-table td.actions-cell{width:22%;}" +
                ".name-cell{font-weight:600;font-size:0.8rem;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}" +
                ".email-cell{opacity:0.9;font-size:0.76rem;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}" +
                ".status-badge{display:inline-block;padding:0.15rem 0.5rem;border-radius:999px;font-size:0.7rem;font-weight:600;}" +
                ".status-active{background:rgba(34,197,94,0.2);color:#86efac;}" +
                ".status-disabled{background:rgba(239,68,68,0.2);color:#fca5a5;}" +
                ".role-cell{overflow:visible;}" +
                ".role-form{margin:0;}" +
                ".role-controls{display:flex;align-items:center;gap:0.4rem;min-width:0;}" +
                ".role-save-btn{flex-shrink:0;padding:0.35rem 0.6rem;}" +
                ".role-dropdown{position:relative;flex:1;min-width:0;}" +
                ".role-dropdown-toggle{width:100%;display:flex;align-items:center;justify-content:space-between;gap:0.4rem;" +
                "padding:0.35rem 0.55rem;border-radius:8px;border:1px solid rgba(255,255,255,0.22);background:#1e293b;color:#f8fafc;" +
                "font-size:0.72rem;font-weight:500;cursor:pointer;text-align:left;min-height:2rem;}" +
                ".role-dropdown-toggle:hover{background:#334155;}" +
                ".role-dropdown.open .role-dropdown-toggle{border-color:#60a5fa;background:#1e3a5f;}" +
                ".role-dropdown-summary{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}" +
                ".role-dropdown-caret{opacity:0.8;font-size:0.7rem;flex-shrink:0;}" +
                ".role-dropdown-panel{display:none;position:fixed;z-index:1000;width:260px;max-height:280px;" +
                "flex-direction:column;padding:0.55rem;border-radius:10px;border:1px solid rgba(255,255,255,0.18);" +
                "background:#0f172a;box-shadow:0 16px 40px rgba(0,0,0,0.45);}" +
                ".role-dropdown-panel[hidden]{display:none !important;}" +
                ".role-dropdown.open .role-dropdown-panel{display:flex;}" +
                ".role-dropdown-search{width:100%;box-sizing:border-box;margin-bottom:0.45rem;padding:0.45rem 0.55rem;" +
                "border-radius:8px;border:1px solid rgba(255,255,255,0.18);background:rgba(255,255,255,0.06);color:#fff;font-size:0.75rem;flex-shrink:0;}" +
                ".role-dropdown-search::placeholder{color:rgba(248,250,252,0.55);}" +
                ".role-checks{display:flex;flex-direction:column;gap:0.2rem;overflow:auto;min-height:0;flex:1;}" +
                ".role-check{display:flex;align-items:center;gap:0.4rem;font-size:0.75rem;opacity:0.95;cursor:pointer;" +
                "padding:0.28rem 0.35rem;border-radius:6px;}" +
                ".role-check:hover{background:rgba(255,255,255,0.06);}" +
                ".role-check input{margin:0;accent-color:#2563eb;}" +
                ".role-check.role-hidden{display:none;}" +
                ".role-field{grid-column:1/-1;}" +
                ".role-field .role-dropdown{max-width:360px;}" +
                ".role-label{display:block;font-size:0.95rem;opacity:0.9;margin-bottom:0.4rem;}" +
                ".role-display{font-size:0.78rem;}" +
                ".users-edit-panel .role-dropdown-panel{position:static;width:100%;max-width:360px;max-height:240px;margin-top:0.4rem;}" +
                ".users-edit-panel .role-dropdown.open .role-dropdown-panel{display:flex;}" +
                ".user-actions{display:flex;flex-wrap:nowrap;gap:0.3rem;align-items:center;}" +
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
                "@media (max-width:1100px){.users-table{table-layout:auto;}.user-actions{flex-wrap:wrap;}}" +
                "</style></head><body>" +
                "<div class=\"container\">" + buildSidebarHtml(currentUsername, currentUserRole, navOptions, "user-item") + "<main class=\"main\">" +
                headerHtml + feedback +
                "<div class=\"users-content\">" + editPanel + userListHtml + "</div>" +
                "</main></div>" +
                "<script>" +
                "(function(){" +
                "const search=document.getElementById('userSearch');" +
                "if(search){search.addEventListener('input',function(e){" +
                "const q=e.target.value.trim().toLowerCase();" +
                "document.querySelectorAll('.users-table tbody tr').forEach(function(row){" +
                "const name=(row.querySelector('.name-cell')?.textContent||'').toLowerCase();" +
                "const email=(row.querySelector('.email-cell')?.textContent||'').toLowerCase();" +
                "row.style.display=!q||name.includes(q)||email.includes(q)?'':'none';" +
                "});});}" +
                "function updateSummary(dropdown){" +
                "const boxes=dropdown.querySelectorAll('.role-check input[type=checkbox]');" +
                "const labels=[];" +
                "boxes.forEach(function(box){if(box.checked){const span=box.parentElement.querySelector('span');labels.push(span?span.textContent:box.value);}});" +
                "const summary=dropdown.querySelector('.role-dropdown-summary');" +
                "if(summary){summary.textContent=labels.length?labels.join(', '):'Select roles...';}" +
                "}" +
                "function positionPanel(dropdown,panel,toggle){" +
                "if(dropdown.closest('.users-edit-panel')){panel.style.top='';panel.style.left='';panel.style.width='';return;}" +
                "const rect=toggle.getBoundingClientRect();" +
                "const width=Math.max(rect.width,240);" +
                "let left=rect.left;" +
                "if(left+width>window.innerWidth-8){left=Math.max(8,window.innerWidth-width-8);}" +
                "let top=rect.bottom+4;" +
                "const maxH=280;" +
                "if(top+Math.min(maxH,220)>window.innerHeight&&rect.top>maxH){top=Math.max(8,rect.top-maxH-4);}" +
                "panel.style.top=top+'px';" +
                "panel.style.left=left+'px';" +
                "panel.style.width=width+'px';" +
                "}" +
                "function closeAll(except){" +
                "document.querySelectorAll('.role-dropdown.open').forEach(function(dd){" +
                "if(dd!==except){dd.classList.remove('open');" +
                "const panel=dd.querySelector('.role-dropdown-panel');" +
                "const toggle=dd.querySelector('.role-dropdown-toggle');" +
                "if(panel){panel.hidden=true;}if(toggle){toggle.setAttribute('aria-expanded','false');}}" +
                "});}" +
                "let ignoreOutsideUntil=0;" +
                "let lastWindowWidth=window.innerWidth;" +
                "function isFinePointer(){try{return window.matchMedia('(hover:hover) and (pointer:fine)').matches;}catch(err){return true;}}" +
                "document.querySelectorAll('[data-role-dropdown]').forEach(function(dropdown){" +
                "const toggle=dropdown.querySelector('.role-dropdown-toggle');" +
                "const panel=dropdown.querySelector('.role-dropdown-panel');" +
                "const filter=dropdown.querySelector('.role-dropdown-search');" +
                "if(!toggle||!panel){return;}" +
                "function openDropdown(){" +
                "closeAll(dropdown);" +
                "dropdown.classList.add('open');" +
                "panel.hidden=false;" +
                "toggle.setAttribute('aria-expanded','true');" +
                "ignoreOutsideUntil=Date.now()+500;" +
                "positionPanel(dropdown,panel,toggle);" +
                "if(filter&&isFinePointer()){filter.focus();filter.select();}" +
                "}" +
                "function closeDropdown(){" +
                "dropdown.classList.remove('open');" +
                "panel.hidden=true;" +
                "toggle.setAttribute('aria-expanded','false');" +
                "}" +
                "toggle.addEventListener('click',function(e){" +
                "e.preventDefault();e.stopPropagation();" +
                "if(dropdown.classList.contains('open')){closeDropdown();}else{openDropdown();}" +
                "});" +
                "panel.addEventListener('click',function(e){e.stopPropagation();});" +
                "panel.addEventListener('touchstart',function(e){e.stopPropagation();},{passive:true});" +
                "if(filter){filter.addEventListener('input',function(){" +
                "const q=filter.value.trim().toLowerCase();" +
                "dropdown.querySelectorAll('.role-check').forEach(function(label){" +
                "const text=label.getAttribute('data-role-label')||'';" +
                "label.classList.toggle('role-hidden',!!q&&!text.includes(q));" +
                "});});}" +
                "dropdown.querySelectorAll('.role-check input[type=checkbox]').forEach(function(box){" +
                "box.addEventListener('change',function(){updateSummary(dropdown);});" +
                "box.addEventListener('click',function(e){e.stopPropagation();});" +
                "});" +
                "updateSummary(dropdown);" +
                "});" +
                "document.addEventListener('click',function(e){" +
                "if(Date.now()<ignoreOutsideUntil){return;}" +
                "if(e.target&&e.target.closest&&e.target.closest('[data-role-dropdown]')){return;}" +
                "closeAll(null);" +
                "});" +
                "document.addEventListener('touchend',function(e){" +
                "if(Date.now()<ignoreOutsideUntil){return;}" +
                "if(e.target&&e.target.closest&&e.target.closest('[data-role-dropdown]')){return;}" +
                "closeAll(null);" +
                "},{passive:true});" +
                "window.addEventListener('resize',function(){" +
                "if(window.innerWidth!==lastWindowWidth){lastWindowWidth=window.innerWidth;closeAll(null);}" +
                "});" +
                "const panelScroll=document.querySelector('.users-panel');" +
                "if(panelScroll){panelScroll.addEventListener('scroll',function(){closeAll(null);},{passive:true});}" +
                "})();" +
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
            UserRecord user = findUserByUsername(username);
            redirect(exchange, user == null ? "/dashboard" : resolveHomePath(user, listNavOptions()));
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

    private static Connection openUsersDb() throws SQLException {
        Connection connection = DriverManager.getConnection(DB_URL);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private static Connection openStatusDb() throws SQLException {
        Connection connection = DriverManager.getConnection(STATUS_DB_URL);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private static String createSession(String username) {
        String sessionId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try (Connection connection = openUsersDb();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO sessions(session_id, username, last_activity_ms) VALUES (?, ?, ?)")) {
            statement.setString(1, sessionId);
            statement.setString(2, username);
            statement.setLong(3, now);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Unable to create session", ex);
        }
        return sessionId;
    }

    private static void destroySession(String sessionId) {
        try (Connection connection = openUsersDb();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM sessions WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            statement.executeUpdate();
        } catch (SQLException ignored) {
            // best-effort cleanup
        }
    }

    private static void destroySessionsForUser(String username) {
        try (Connection connection = openUsersDb();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM sessions WHERE username = ?")) {
            statement.setString(1, username);
            statement.executeUpdate();
        } catch (SQLException ignored) {
            // best-effort cleanup
        }
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

        try (Connection connection = openUsersDb();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT username, last_activity_ms FROM sessions WHERE session_id = ?")) {
            select.setString(1, sessionId);
            try (var rs = select.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String username = rs.getString("username");
                long lastActivityMs = rs.getLong("last_activity_ms");
                long now = System.currentTimeMillis();
                if (now - lastActivityMs > SESSION_TIMEOUT_MS) {
                    destroySession(sessionId);
                    exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE_NAME + "=deleted; Path=/; Max-Age=0; HttpOnly");
                    return null;
                }
                // Touch at most every 30s to cut SQLite write contention across microservices.
                if (now - lastActivityMs > 30_000L) {
                    try (PreparedStatement touch = connection.prepareStatement(
                            "UPDATE sessions SET last_activity_ms = ? WHERE session_id = ?")) {
                        touch.setLong(1, now);
                        touch.setString(2, sessionId);
                        touch.executeUpdate();
                    }
                }
                return username;
            }
        } catch (SQLException ex) {
            return null;
        }
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

    private static List<String> parseFormValues(String body, String key) {
        List<String> values = new ArrayList<>();
        if (body == null || body.isEmpty() || key == null || key.isEmpty()) {
            return values;
        }
        String[] pairs = body.split("&");
        for (String pair : pairs) {
            String[] parts = pair.split("=", 2);
            String pairKey = urlDecode(parts[0]);
            if (!key.equals(pairKey)) {
                continue;
            }
            String value = parts.length > 1 ? urlDecode(parts[1]) : "";
            if (!value.isBlank()) {
                values.add(value.trim());
            }
        }
        return values;
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

    private static String progressColor(int percent) {
        int p = Math.max(0, Math.min(100, percent));
        if (p >= 100) {
            return "#16a34a";
        }
        if (p >= 75) {
            return "#65a30d";
        }
        if (p >= 50) {
            return "#ca8a04";
        }
        if (p >= 25) {
            return "#ea580c";
        }
        return "#dc2626";
    }

    private static final String[][] CITY_ALIASES = {
            {"riyadh", "riyad", "ar riyadh"},
            {"jeddah", "jiddah", "jedda", "jiddha"},
            {"makkah", "mecca", "mecca city"},
            {"madinah", "medina", "al madinah"},
            {"dammam", "ad dammam"},
            {"khobar", "al khobar"},
            {"jubail", "al jubail"},
            {"tabuk"},
            {"abha"},
            {"taif", "at taif"},
            {"yanbu"},
            {"najran"},
            {"jazan", "jizan", "gizan"},
            {"hail", "ha'il"},
            {"buraidah", "buraydah", "qassim", "al qassim"},
            {"khamis mushait", "khamis mushayt"},
            {"neom"}
    };

    private static final double[][] CITY_COORDS = {
            {24.7136, 46.6753}, // riyadh
            {21.4858, 39.1925}, // jeddah
            {21.3891, 39.8579}, // makkah
            {24.5247, 39.5692}, // madinah
            {26.4207, 50.0888}, // dammam
            {26.2172, 50.1971}, // khobar
            {27.0174, 49.6225}, // jubail
            {28.3838, 36.5550}, // tabuk
            {18.2164, 42.5053}, // abha
            {21.2703, 40.4158}, // taif
            {24.0895, 38.0618}, // yanbu
            {17.5651, 44.2289}, // najran
            {16.8892, 42.5511}, // jazan
            {27.5114, 41.7208}, // hail
            {26.3260, 43.9750}, // buraidah/qassim
            {18.3000, 42.7333}, // khamis mushait
            {28.1120, 35.0760}  // neom
    };

    private static String normalizeVenueLabel(String label) {
        return label.trim().toLowerCase(java.util.Locale.ROOT)
                .replace('-', ' ')
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean containsWholePhrase(String text, String phrase) {
        int idx = 0;
        while ((idx = text.indexOf(phrase, idx)) >= 0) {
            boolean startOk = idx == 0 || !Character.isLetterOrDigit(text.charAt(idx - 1));
            int end = idx + phrase.length();
            boolean endOk = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            idx++;
        }
        return false;
    }

    /** Returns city index in CITY_COORDS, or -1 if no known city is found in the venue name. */
    private static int resolveCityIndex(String label) {
        if (label == null || label.isBlank()) {
            return -1;
        }
        String key = normalizeVenueLabel(label);

        int bestIndex = -1;
        int bestAliasLength = -1;
        for (int i = 0; i < CITY_ALIASES.length; i++) {
            for (String alias : CITY_ALIASES[i]) {
                if (key.equals(alias) || containsWholePhrase(key, alias)) {
                    if (alias.length() > bestAliasLength) {
                        bestAliasLength = alias.length();
                        bestIndex = i;
                    }
                }
            }
        }
        return bestIndex;
    }

    private static final double MAP_WIDTH = 860;
    private static final double MAP_HEIGHT = 660;
    private static final double MAP_PAD_X = 130;
    private static final double MAP_PAD_Y = 80;
    private static final double[] CITY_BOUNDARY_RX = {
            42, 36, 34, 36, 34, 28, 30, 32, 30, 30, 28, 30, 30, 30, 34, 30, 38
    };
    private static final double[] CITY_BOUNDARY_RY = {
            36, 30, 28, 30, 28, 24, 26, 28, 26, 26, 24, 26, 26, 26, 28, 26, 32
    };

    private static double[] offsetMapPoint(double x, double y, int indexAtCity) {
        if (indexAtCity <= 0) {
            return new double[]{x, y};
        }
        double angle = (indexAtCity * 1.15) + 0.4;
        double radius = 34.0 + (indexAtCity - 1) * 18.0;
        return new double[]{x + Math.cos(angle) * radius, y + Math.sin(angle) * radius};
    }

    private static double[] projectSaudiMap(double lat, double lon) {
        double minLon = 33.8;
        double maxLon = 56.0;
        double minLat = 15.8;
        double maxLat = 32.4;
        double innerW = MAP_WIDTH - (2 * MAP_PAD_X);
        double innerH = MAP_HEIGHT - (2 * MAP_PAD_Y);
        double x = MAP_PAD_X + ((lon - minLon) / (maxLon - minLon)) * innerW;
        double y = MAP_PAD_Y + ((maxLat - lat) / (maxLat - minLat)) * innerH;
        return new double[]{x, y};
    }

    private static String pathFromLatLon(double[][] points) {
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < points.length; i++) {
            double[] xy = projectSaudiMap(points[i][0], points[i][1]);
            path.append(i == 0 ? "M" : " L")
                    .append(String.format(java.util.Locale.US, "%.1f,%.1f", xy[0], xy[1]));
        }
        path.append(" Z");
        return path.toString();
    }

    private static String saudiOutlinePath() {
        double[][] points = {
                {29.35, 34.95}, {28.10, 34.60}, {26.20, 36.40}, {24.10, 37.80}, {22.20, 38.90},
                {20.00, 40.40}, {18.20, 41.50}, {16.90, 42.55}, {16.40, 42.80}, {17.20, 44.40},
                {17.80, 47.20}, {18.90, 50.20}, {19.80, 52.20}, {22.00, 55.20}, {24.50, 51.60},
                {26.40, 50.20}, {27.50, 49.20}, {28.50, 48.40}, {29.10, 46.60}, {30.00, 44.00},
                {31.20, 41.50}, {32.15, 39.20}, {31.80, 37.20}, {30.50, 36.00}
        };
        return pathFromLatLon(points);
    }

    private static String cityBoundariesSvg(java.util.Set<Integer> activeCities, java.util.Map<Integer, String> cityColors) {
        StringBuilder boundaries = new StringBuilder();
        for (int i = 0; i < CITY_COORDS.length; i++) {
            double[] xy = projectSaudiMap(CITY_COORDS[i][0], CITY_COORDS[i][1]);
            boolean active = activeCities.contains(i);
            String stroke = active ? cityColors.getOrDefault(i, "#93c5fd") : "rgba(147,197,253,0.45)";
            String fill = active
                    ? hexToRgba(cityColors.getOrDefault(i, "#93c5fd"), 0.18)
                    : "rgba(147,197,253,0.05)";
            boundaries.append("<ellipse class=\"city-boundary\" cx=\"")
                    .append(String.format(java.util.Locale.US, "%.1f", xy[0]))
                    .append("\" cy=\"").append(String.format(java.util.Locale.US, "%.1f", xy[1]))
                    .append("\" rx=\"").append(String.format(java.util.Locale.US, "%.1f", CITY_BOUNDARY_RX[i]))
                    .append("\" ry=\"").append(String.format(java.util.Locale.US, "%.1f", CITY_BOUNDARY_RY[i]))
                    .append("\" fill=\"").append(fill)
                    .append("\" stroke=\"").append(stroke)
                    .append("\" stroke-width=\"").append(active ? "2.5" : "1.2")
                    .append("\" stroke-dasharray=\"").append(active ? "0" : "5 4")
                    .append("\"/>");
        }
        return boundaries.toString();
    }

    private static String hexToRgba(String hex, double alpha) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() != 6) {
            return "rgba(147,197,253," + alpha + ")";
        }
        int r = Integer.parseInt(h.substring(0, 2), 16);
        int g = Integer.parseInt(h.substring(2, 4), 16);
        int b = Integer.parseInt(h.substring(4, 6), 16);
        return "rgba(" + r + "," + g + "," + b + "," + alpha + ")";
    }

    private static String markerLabelAnchor(double x) {
        if (x < MAP_PAD_X + 40) {
            return "start";
        }
        if (x > MAP_WIDTH - MAP_PAD_X - 40) {
            return "end";
        }
        return "middle";
    }

    private static double markerLabelX(double x) {
        if (x < MAP_PAD_X + 40) {
            return x + 18;
        }
        if (x > MAP_WIDTH - MAP_PAD_X - 40) {
            return x - 18;
        }
        return x;
    }

    private static String buildMapviewPage(String username, String firstName, String userRole, java.util.List<NavOption> navOptions, java.util.List<OptionProgress> progressList) {
        StringBuilder markers = new StringBuilder();
        StringBuilder legendRows = new StringBuilder();
        StringBuilder unmapped = new StringBuilder();
        java.util.Map<Integer, Integer> markersPerCity = new java.util.HashMap<>();
        java.util.Set<Integer> activeCities = new java.util.HashSet<>();
        java.util.Map<Integer, String> cityColors = new java.util.HashMap<>();
        java.util.List<double[]> placedPoints = new java.util.ArrayList<>();

        for (OptionProgress progress : progressList) {
            String color = progressColor(progress.overallPercent);
            int cityIndex = resolveCityIndex(progress.option.label);
            legendRows.append("<tr>")
                    .append("<td><span class=\"swatch\" style=\"background:").append(color).append(";\"></span>")
                    .append(escapeHtml(progress.option.label)).append("</td>")
                    .append("<td>").append(progress.overallPercent).append("%</td>")
                    .append("</tr>");

            if (cityIndex < 0) {
                unmapped.append("<li>").append(escapeHtml(progress.option.label))
                        .append(" (").append(progress.overallPercent).append("%)</li>");
                continue;
            }

            activeCities.add(cityIndex);
            cityColors.putIfAbsent(cityIndex, color);

            int slot = markersPerCity.merge(cityIndex, 1, Integer::sum) - 1;
            double[] xy = projectSaudiMap(CITY_COORDS[cityIndex][0], CITY_COORDS[cityIndex][1]);
            xy = offsetMapPoint(xy[0], xy[1], slot);

            // Nudge away from already placed markers to reduce overlap (e.g. Jeddah / Makkah).
            for (int pass = 0; pass < 4; pass++) {
                boolean moved = false;
                for (double[] other : placedPoints) {
                    double dx = xy[0] - other[0];
                    double dy = xy[1] - other[1];
                    double dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist < 46 && dist > 0.01) {
                        double push = (46 - dist) / 2.0;
                        xy[0] += (dx / dist) * push;
                        xy[1] += (dy / dist) * push;
                        moved = true;
                    }
                }
                if (!moved) {
                    break;
                }
            }
            placedPoints.add(new double[]{xy[0], xy[1]});

            String anchor = markerLabelAnchor(xy[0]);
            double labelX = markerLabelX(xy[0]);
            double labelY = xy[1] - 22 - (slot * 16);
            markers.append("<g class=\"venue-marker\">")
                    .append("<circle cx=\"").append(String.format(java.util.Locale.US, "%.1f", xy[0]))
                    .append("\" cy=\"").append(String.format(java.util.Locale.US, "%.1f", xy[1]))
                    .append("\" r=\"12\" fill=\"").append(color).append("\" stroke=\"#ffffff\" stroke-width=\"2\"/>")
                    .append("<text x=\"").append(String.format(java.util.Locale.US, "%.1f", labelX))
                    .append("\" y=\"").append(String.format(java.util.Locale.US, "%.1f", labelY))
                    .append("\" text-anchor=\"").append(anchor).append("\" class=\"marker-label\">")
                    .append(escapeHtml(progress.option.label))
                    .append(" - ").append(progress.overallPercent).append("%</text>")
                    .append("</g>");
        }

        String unmappedHtml = unmapped.length() == 0
                ? ""
                : "<div class=\"unmapped\"><h3>Venues without map location</h3><ul>" + unmapped + "</ul>" +
                "<p class=\"hint\">Use a Saudi city name (for example Jeddah or Riyadh) as the venue name to place it on the map.</p></div>";

        String viewBox = "0 0 " + ((int) MAP_WIDTH) + " " + ((int) MAP_HEIGHT);

        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>Mapview</title><style>" + sidebarLayoutStyles() +
                " .top-actions{display:flex;justify-content:flex-end;gap:0.75rem;margin-bottom:1.5rem;}" +
                " .map-layout{display:flex;gap:1.5rem;align-items:flex-start;flex-wrap:wrap;}" +
                " .map-card,.legend-card{padding:1.5rem;border-radius:16px;background:rgba(255,255,255,0.06);box-shadow:0 12px 30px rgba(15,23,42,0.2);}" +
                " .map-card{flex:2 1 520px;min-width:0;}" +
                " .legend-card{flex:1 1 240px;}" +
                " .map-card h1,.legend-card h2{margin:0 0 0.75rem;}" +
                " .map-card p{margin:0 0 1rem;opacity:0.9;}" +
                " .map-viewport{position:relative;border-radius:12px;overflow:hidden;background:radial-gradient(circle at 30% 20%,#1e3a8a,#0f172a 70%);touch-action:none;}" +
                " .map-zoom-controls{position:absolute;top:12px;right:12px;z-index:2;display:flex;flex-direction:column;gap:0.4rem;}" +
                " .map-zoom-controls button{width:36px;height:36px;border:none;border-radius:10px;background:rgba(15,23,42,0.85);color:#f8fafc;font-size:1.2rem;font-weight:700;cursor:pointer;line-height:1;}" +
                " .map-zoom-controls button:hover{background:#2563eb;}" +
                " .map-hint{margin:0.65rem 0 0;font-size:0.82rem;opacity:0.75;}" +
                " .map-svg{width:100%;height:auto;display:block;cursor:grab;user-select:none;}" +
                " .map-svg.dragging{cursor:grabbing;}" +
                " .land{fill:#1d4ed8;stroke:#93c5fd;stroke-width:2;opacity:0.85;}" +
                " .city-boundary{opacity:0.95;}" +
                " .marker-label{fill:#f8fafc;font-size:13px;font-family:Arial,Helvetica,sans-serif;paint-order:stroke;stroke:#0f172a;stroke-width:3px;}" +
                " .legend-table{width:100%;border-collapse:collapse;}" +
                " .legend-table th,.legend-table td{padding:0.65rem 0.4rem;text-align:left;border-bottom:1px solid rgba(255,255,255,0.08);}" +
                " .legend-table th{font-size:0.72rem;text-transform:uppercase;letter-spacing:0.06em;opacity:0.7;}" +
                " .swatch{display:inline-block;width:12px;height:12px;border-radius:50%;margin-right:0.55rem;vertical-align:middle;border:1px solid rgba(255,255,255,0.5);}" +
                " .scale{display:flex;flex-wrap:wrap;gap:0.6rem;margin:0 0 1rem;font-size:0.85rem;opacity:0.95;}" +
                " .scale span{display:inline-flex;align-items:center;gap:0.35rem;}" +
                " .unmapped{margin-top:1rem;}" +
                " .unmapped h3{margin:0 0 0.5rem;font-size:1rem;}" +
                " .unmapped ul{margin:0;padding-left:1.2rem;}" +
                " .hint{margin:0.6rem 0 0;font-size:0.85rem;opacity:0.75;}" +
                "</style></head><body>" +
                "<div class=\"container\">" + buildSidebarHtml(username, userRole, navOptions, "nav-item") + "<main class=\"main\">" +
                "<div class=\"top-actions\"><a class=\"button\" href=\"/profile\">Edit Profile</a><a class=\"button\" href=\"/logout\">Logout</a></div>" +
                "<div class=\"map-layout\">" +
                "<div class=\"map-card\"><h1>Mapview</h1>" +
                "<p>Saudi Arabia venues colored by completion progress.</p>" +
                "<div class=\"scale\">" +
                "<span><i class=\"swatch\" style=\"background:#dc2626;\"></i>0-24%</span>" +
                "<span><i class=\"swatch\" style=\"background:#ea580c;\"></i>25-49%</span>" +
                "<span><i class=\"swatch\" style=\"background:#ca8a04;\"></i>50-74%</span>" +
                "<span><i class=\"swatch\" style=\"background:#65a30d;\"></i>75-99%</span>" +
                "<span><i class=\"swatch\" style=\"background:#16a34a;\"></i>100%</span>" +
                "</div>" +
                "<div class=\"map-viewport\">" +
                "<div class=\"map-zoom-controls\">" +
                "<button type=\"button\" id=\"zoom-in\" title=\"Zoom in\" aria-label=\"Zoom in\">+</button>" +
                "<button type=\"button\" id=\"zoom-out\" title=\"Zoom out\" aria-label=\"Zoom out\">&minus;</button>" +
                "<button type=\"button\" id=\"zoom-reset\" title=\"Reset view\" aria-label=\"Reset view\">&#8634;</button>" +
                "</div>" +
                "<svg id=\"map-svg\" class=\"map-svg\" viewBox=\"" + viewBox + "\" role=\"img\" aria-label=\"Map of Saudi Arabia with venue progress\">" +
                "<path class=\"land\" d=\"" + saudiOutlinePath() + "\"/>" +
                cityBoundariesSvg(activeCities, cityColors) +
                markers +
                "</svg></div>" +
                "<p class=\"map-hint\">Use + / &minus; or the mouse wheel to zoom. Drag the map to pan for a detailed view.</p>" +
                unmappedHtml +
                "</div>" +
                "<div class=\"legend-card\"><h2>Venue Progress</h2>" +
                "<table class=\"legend-table\"><thead><tr><th>Venue</th><th>Progress</th></tr></thead><tbody>" +
                (legendRows.length() == 0
                        ? "<tr><td colspan=\"2\">No venues yet. Add venues in Admin Panel.</td></tr>"
                        : legendRows.toString()) +
                "</tbody></table></div>" +
                "</div></main></div>" +
                mapZoomScript((int) MAP_WIDTH, (int) MAP_HEIGHT) +
                "</body></html>";
    }

    private static String mapZoomScript(int baseWidth, int baseHeight) {
        return "<script>(function(){" +
                "var svg=document.getElementById('map-svg');" +
                "if(!svg)return;" +
                "var baseW=" + baseWidth + ",baseH=" + baseHeight + ";" +
                "var minZoom=1,maxZoom=6;" +
                "var vb={x:0,y:0,w:baseW,h:baseH};" +
                "var dragging=false,lastX=0,lastY=0;" +
                "function apply(){svg.setAttribute('viewBox',vb.x+' '+vb.y+' '+vb.w+' '+vb.h);}" +
                "function clampView(){" +
                "vb.w=Math.min(baseW,Math.max(baseW/maxZoom,vb.w));" +
                "vb.h=vb.w*(baseH/baseW);" +
                "vb.x=Math.min(baseW-vb.w,Math.max(0,vb.x));" +
                "vb.y=Math.min(baseH-vb.h,Math.max(0,vb.y));" +
                "}" +
                "function zoomAt(factor,clientX,clientY){" +
                "var rect=svg.getBoundingClientRect();" +
                "var px=(clientX-rect.left)/rect.width;" +
                "var py=(clientY-rect.top)/rect.height;" +
                "var mx=vb.x+px*vb.w,my=vb.y+py*vb.h;" +
                "var nextW=vb.w/factor,nextH=vb.h/factor;" +
                "if(nextW>baseW){nextW=baseW;nextH=baseH;}" +
                "if(nextW<baseW/maxZoom){nextW=baseW/maxZoom;nextH=baseH/maxZoom;}" +
                "vb.w=nextW;vb.h=nextH;" +
                "vb.x=mx-px*vb.w;vb.y=my-py*vb.h;" +
                "clampView();apply();" +
                "}" +
                "function zoomCenter(factor){" +
                "var rect=svg.getBoundingClientRect();" +
                "zoomAt(factor,rect.left+rect.width/2,rect.top+rect.height/2);" +
                "}" +
                "document.getElementById('zoom-in').addEventListener('click',function(){zoomCenter(1.25);});" +
                "document.getElementById('zoom-out').addEventListener('click',function(){zoomCenter(0.8);});" +
                "document.getElementById('zoom-reset').addEventListener('click',function(){vb={x:0,y:0,w:baseW,h:baseH};apply();});" +
                "svg.addEventListener('wheel',function(e){" +
                "e.preventDefault();" +
                "zoomAt(e.deltaY<0?1.2:0.85,e.clientX,e.clientY);" +
                "},{passive:false});" +
                "svg.addEventListener('pointerdown',function(e){" +
                "if(e.button!==0)return;" +
                "dragging=true;lastX=e.clientX;lastY=e.clientY;" +
                "svg.classList.add('dragging');" +
                "svg.setPointerCapture(e.pointerId);" +
                "});" +
                "svg.addEventListener('pointermove',function(e){" +
                "if(!dragging)return;" +
                "var rect=svg.getBoundingClientRect();" +
                "var dx=(e.clientX-lastX)/rect.width*vb.w;" +
                "var dy=(e.clientY-lastY)/rect.height*vb.h;" +
                "vb.x-=dx;vb.y-=dy;lastX=e.clientX;lastY=e.clientY;" +
                "clampView();apply();" +
                "});" +
                "function endDrag(e){dragging=false;svg.classList.remove('dragging');}" +
                "svg.addEventListener('pointerup',endDrag);" +
                "svg.addEventListener('pointercancel',endDrag);" +
                "})();</script>";
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
            Map<String, Integer> percentByStatus;
            try {
                percentByStatus = statusPercentMap();
            } catch (SQLException ex) {
                percentByStatus = Map.of();
            }
            StringBuilder detailRows = new StringBuilder();
            for (WorkItem item : selected.workItems) {
                int pct = statusToPercent(item.status, percentByStatus);
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

    // --- JSON API helpers and handlers for React SPA ---

    private static String jsonEscape(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendJsonMessage(HttpExchange exchange, int statusCode, String message) throws IOException {
        sendJson(exchange, statusCode, "{\"message\":\"" + jsonEscape(message) + "\"}");
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String jsonGetString(String json, String key) {
        if (json == null || key == null) {
            return null;
        }
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length()) {
            return null;
        }
        char c = json.charAt(i);
        if (c == '"') {
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < json.length()) {
                char ch = json.charAt(i++);
                if (ch == '\\' && i < json.length()) {
                    char n = json.charAt(i++);
                    sb.append(n == 'n' ? '\n' : n == 'r' ? '\r' : n == 't' ? '\t' : n);
                } else if (ch == '"') {
                    break;
                } else {
                    sb.append(ch);
                }
            }
            return sb.toString();
        }
        if (c == 'n' && json.startsWith("null", i)) {
            return null;
        }
        int end = i;
        while (end < json.length() && ",}]".indexOf(json.charAt(end)) < 0) {
            end++;
        }
        return json.substring(i, end).trim();
    }

    private static int jsonGetInt(String json, String key, int fallback) {
        String raw = jsonGetString(json, key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static List<String> jsonGetStringArray(String json, String key) {
        List<String> out = new ArrayList<>();
        if (json == null || key == null) {
            return out;
        }
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) {
            return out;
        }
        int bracket = json.indexOf('[', idx + pattern.length());
        if (bracket < 0) {
            return out;
        }
        int end = json.indexOf(']', bracket + 1);
        if (end < 0) {
            return out;
        }
        String inner = json.substring(bracket + 1, end);
        int i = 0;
        while (i < inner.length()) {
            while (i < inner.length() && (Character.isWhitespace(inner.charAt(i)) || inner.charAt(i) == ',')) {
                i++;
            }
            if (i >= inner.length()) {
                break;
            }
            if (inner.charAt(i) != '"') {
                break;
            }
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < inner.length()) {
                char ch = inner.charAt(i++);
                if (ch == '\\' && i < inner.length()) {
                    sb.append(inner.charAt(i++));
                } else if (ch == '"') {
                    break;
                } else {
                    sb.append(ch);
                }
            }
            out.add(sb.toString());
        }
        return out;
    }

    private static String resolveSpaHomePath(UserRecord user, java.util.List<NavOption> navOptions) {
        if (hasAdminRole(user.role) || hasUserRole(user.role) || isStandardUserRole(user.role)) {
            return "/dashboard";
        }
        List<NavOption> venues = matchedVenueOptions(user.role, navOptions);
        return venues.isEmpty() ? "/dashboard" : "/venues/" + venues.get(0).id;
    }

    private static String buildNavJson(UserRecord user, java.util.List<NavOption> navOptions) {
        StringBuilder nav = new StringBuilder("[");
        boolean first = true;
        if (hasAdminRole(user.role)) {
            first = appendNavItem(nav, first, "Dashboard", "/dashboard");
            first = appendNavItem(nav, first, "Users", "/users");
            first = appendNavItem(nav, first, "Admin Panel", "/admin");
            first = appendNavItem(nav, first, "Status", "/status");
            first = appendNavItem(nav, first, "Mapview", "/mapview");
            for (NavOption option : navOptions) {
                first = appendNavItem(nav, first, option.label, "/venues/" + option.id);
            }
        } else {
            List<NavOption> venueMatches = matchedVenueOptions(user.role, navOptions);
            boolean standardAccess = hasUserRole(user.role) || venueMatches.isEmpty();
            if (standardAccess) {
                first = appendNavItem(nav, first, "Dashboard", "/dashboard");
                first = appendNavItem(nav, first, "Users", "/users");
                first = appendNavItem(nav, first, "Mapview", "/mapview");
            } else {
                first = appendNavItem(nav, first, "Mapview", "/mapview");
            }
            for (NavOption matched : venueMatches) {
                first = appendNavItem(nav, first, matched.label, "/venues/" + matched.id);
            }
        }
        nav.append("]");
        return nav.toString();
    }

    private static boolean appendNavItem(StringBuilder nav, boolean first, String label, String href) {
        if (!first) {
            nav.append(',');
        }
        nav.append("{\"id\":0,\"label\":\"").append(jsonEscape(label))
                .append("\",\"href\":\"").append(jsonEscape(href)).append("\"}");
        return false;
    }

    private static String buildMeJson(String username, UserRecord user, java.util.List<NavOption> navOptions) throws SQLException {
        Map<String, String> profile = findUserProfile(username);
        String firstName = profile != null ? profile.getOrDefault("first_name", user.firstName) : user.firstName;
        String lastName = profile != null ? profile.getOrDefault("last_name", "") : "";
        String email = profile != null ? profile.getOrDefault("email", "") : "";
        List<String> roles = normalizeRoles(parseRoles(user.role));
        StringBuilder rolesJson = new StringBuilder("[");
        for (int i = 0; i < roles.size(); i++) {
            if (i > 0) {
                rolesJson.append(',');
            }
            rolesJson.append('"').append(jsonEscape(roles.get(i))).append('"');
        }
        rolesJson.append(']');
        return "{"
                + "\"username\":\"" + jsonEscape(username) + "\","
                + "\"firstName\":\"" + jsonEscape(firstName) + "\","
                + "\"lastName\":\"" + jsonEscape(lastName) + "\","
                + "\"email\":\"" + jsonEscape(email) + "\","
                + "\"role\":\"" + jsonEscape(user.role) + "\","
                + "\"roles\":" + rolesJson + ","
                + "\"isAdmin\":" + (hasAdminRole(user.role) ? "true" : "false") + ","
                + "\"mustChangePassword\":" + (user.mustChangePassword ? "true" : "false") + ","
                + "\"homePath\":\"" + jsonEscape(resolveSpaHomePath(user, navOptions)) + "\","
                + "\"nav\":" + buildNavJson(user, navOptions)
                + "}";
    }

    private static void apiLogin(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonMessage(exchange, 405, "Method not allowed");
            return;
        }
        String body = readBody(exchange);
        String username = jsonGetString(body, "username");
        String password = jsonGetString(body, "password");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            sendJsonMessage(exchange, 400, "Please provide both username and password.");
            return;
        }
        username = username.trim();
        password = password.trim();
        try {
            if (!userExists(username)) {
                sendJsonMessage(exchange, 401, "Invalid User");
                return;
            }
            UserRecord user = findUserByCredentials(username, password);
            if (user == null) {
                sendJsonMessage(exchange, 401, "Invalid Password");
                return;
            }
            if (!user.enabled) {
                sendJsonMessage(exchange, 403, "This account has been disabled. Please contact an administrator.");
                return;
            }
            String sessionId = createSession(username);
            exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE_NAME + "=" + sessionId + "; Path=/; HttpOnly");
            sendJson(exchange, 200, buildMeJson(username, user, listNavOptions()));
        } catch (SQLException ex) {
            sendJsonMessage(exchange, 500, "Unable to verify credentials right now.");
        }
    }

    private static void apiLogout(HttpExchange exchange) throws IOException {
        String sessionId = getSessionIdFromCookie(exchange);
        if (sessionId != null) {
            destroySession(sessionId);
        }
        exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE_NAME + "=deleted; Path=/; Max-Age=0; HttpOnly");
        sendJsonMessage(exchange, 200, "Logged out");
    }

    private static void apiMe(HttpExchange exchange) throws IOException {
        String username = getSessionUsername(exchange);
        if (username == null) {
            sendJsonMessage(exchange, 401, "Unauthorized");
            return;
        }
        try {
            UserRecord user = findUserByUsername(username);
            if (user == null || !user.enabled) {
                sendJsonMessage(exchange, 401, "Unauthorized");
                return;
            }
            sendJson(exchange, 200, buildMeJson(username, user, listNavOptions()));
        } catch (SQLException ex) {
            sendJsonMessage(exchange, 500, "Unable to load session");
        }
    }

    private static void apiNav(HttpExchange exchange) throws IOException {
        apiMe(exchange);
    }

    private static void apiProfile(HttpExchange exchange) throws IOException {
        String username = getSessionUsername(exchange);
        if (username == null) {
            sendJsonMessage(exchange, 401, "Unauthorized");
            return;
        }
        try {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> profile = findUserProfile(username);
                if (profile == null) {
                    sendJsonMessage(exchange, 404, "Profile not found");
                    return;
                }
                sendJson(exchange, 200, "{"
                        + "\"username\":\"" + jsonEscape(username) + "\","
                        + "\"firstName\":\"" + jsonEscape(profile.get("first_name")) + "\","
                        + "\"lastName\":\"" + jsonEscape(profile.get("last_name")) + "\","
                        + "\"email\":\"" + jsonEscape(profile.get("email")) + "\""
                        + "}");
                return;
            }
            if ("PUT".equalsIgnoreCase(exchange.getRequestMethod()) || "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = readBody(exchange);
                String firstName = nullToEmpty(jsonGetString(body, "firstName")).trim();
                String lastName = nullToEmpty(jsonGetString(body, "lastName")).trim();
                String email = nullToEmpty(jsonGetString(body, "email")).trim();
                String password = nullToEmpty(jsonGetString(body, "password")).trim();
                if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty()) {
                    sendJsonMessage(exchange, 400, "First name, last name, and email are required.");
                    return;
                }
                updateUserProfile(username, firstName, lastName, email, password);
                sendJsonMessage(exchange, 200, "Profile updated successfully.");
                return;
            }
            sendJsonMessage(exchange, 405, "Method not allowed");
        } catch (SQLException ex) {
            String message = ex.getMessage();
            if (message != null && message.contains("UNIQUE")) {
                sendJsonMessage(exchange, 400, "The email address is already in use.");
            } else {
                sendJsonMessage(exchange, 500, "Unable to save profile changes.");
            }
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static void apiChangePassword(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonMessage(exchange, 405, "Method not allowed");
            return;
        }
        String username = getSessionUsername(exchange);
        if (username == null) {
            sendJsonMessage(exchange, 401, "Unauthorized");
            return;
        }
        String body = readBody(exchange);
        String password = nullToEmpty(jsonGetString(body, "password")).trim();
        String confirmPassword = nullToEmpty(jsonGetString(body, "confirmPassword")).trim();
        if (password.isEmpty() || confirmPassword.isEmpty()) {
            sendJsonMessage(exchange, 400, "Both password fields are required.");
            return;
        }
        if (!password.equals(confirmPassword)) {
            sendJsonMessage(exchange, 400, "Passwords do not match. Please re-enter them.");
            return;
        }
        try {
            updatePasswordAndClearResetFlag(username, password);
            UserRecord user = findUserByUsername(username);
            String home = user == null ? "/dashboard" : resolveSpaHomePath(user, listNavOptions());
            sendJson(exchange, 200, "{\"message\":\"Password updated\",\"homePath\":\"" + jsonEscape(home) + "\"}");
        } catch (SQLException ex) {
            sendJsonMessage(exchange, 500, "Unable to update your password right now.");
        }
    }

    private static void apiVenues(HttpExchange exchange) throws IOException {
        String username = getSessionUsername(exchange);
        if (username == null) {
            sendJsonMessage(exchange, 401, "Unauthorized");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String suffix = path.substring("/api/venues".length());
        if (suffix.startsWith("/")) {
            suffix = suffix.substring(1);
        }
        if (suffix.isEmpty()) {
            sendJsonMessage(exchange, 400, "Venue id required");
            return;
        }
        int slash = suffix.indexOf('/');
        String idPart = slash >= 0 ? suffix.substring(0, slash) : suffix;
        Integer optionId = parseNavOptionId(idPart);
        if (optionId == null) {
            sendJsonMessage(exchange, 400, "Invalid venue id");
            return;
        }
        try {
            UserRecord user = findUserByUsername(username);
            if (user == null || !user.enabled) {
                sendJsonMessage(exchange, 401, "Unauthorized");
                return;
            }
            NavOption option = findNavOptionById(optionId);
            if (option == null) {
                sendJsonMessage(exchange, 404, "Venue not found");
                return;
            }
            if (!canAccessCustomPage(user, option)) {
                sendJsonMessage(exchange, 403, "Forbidden");
                return;
            }
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                java.util.List<WorkItem> workItems = listWorkItems(optionId);
                java.util.List<StatusDef> statusDefs = listStatusDefs();
                StringBuilder items = new StringBuilder("[");
                for (int i = 0; i < workItems.size(); i++) {
                    WorkItem item = workItems.get(i);
                    if (i > 0) items.append(',');
                    items.append("{\"name\":\"").append(jsonEscape(item.name))
                            .append("\",\"status\":\"").append(jsonEscape(item.status)).append("\"}");
                }
                items.append(']');
                StringBuilder statuses = new StringBuilder("[");
                for (int i = 0; i < statusDefs.size(); i++) {
                    StatusDef s = statusDefs.get(i);
                    if (i > 0) statuses.append(',');
                    statuses.append("{\"id\":").append(s.id)
                            .append(",\"label\":\"").append(jsonEscape(s.label))
                            .append("\",\"percent\":").append(s.percentValue)
                            .append(",\"sortOrder\":").append(s.sortOrder).append('}');
                }
                statuses.append(']');
                sendJson(exchange, 200, "{\"id\":" + option.id
                        + ",\"label\":\"" + jsonEscape(option.label) + "\""
                        + ",\"workItems\":" + items
                        + ",\"statuses\":" + statuses + "}");
                return;
            }
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = readBody(exchange);
                String itemName = nullToEmpty(jsonGetString(body, "itemName")).trim();
                String status = nullToEmpty(jsonGetString(body, "status")).trim();
                if (itemName.isEmpty() || !isValidWorkItemName(itemName) || !isValidWorkItemStatus(status)) {
                    sendJsonMessage(exchange, 400, "Invalid work item status update.");
                    return;
                }
                updateWorkItemStatus(optionId, itemName, status);
                sendJsonMessage(exchange, 200, "Updated");
                return;
            }
            sendJsonMessage(exchange, 405, "Method not allowed");
        } catch (SQLException ex) {
            sendJsonMessage(exchange, 500, "Unable to load venue");
        }
    }

    private static void apiUsers(HttpExchange exchange) throws IOException {
        String currentUsername = getSessionUsername(exchange);
        if (currentUsername == null) {
            sendJsonMessage(exchange, 401, "Unauthorized");
            return;
        }
        try {
            UserRecord current = findUserByUsername(currentUsername);
            if (current == null || !current.enabled) {
                sendJsonMessage(exchange, 401, "Unauthorized");
                return;
            }
            if (!hasAdminRole(current.role) && !hasUserRole(current.role)) {
                sendJsonMessage(exchange, 403, "Forbidden");
                return;
            }
            boolean canManage = hasAdminRole(current.role);
            java.util.List<NavOption> navOptions = listNavOptions();

            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                var users = listAllUsers();
                StringBuilder usersJson = new StringBuilder("[");
                for (int i = 0; i < users.size(); i++) {
                    UserEntry u = users.get(i);
                    if (i > 0) usersJson.append(',');
                    List<String> roles = normalizeRoles(parseRoles(u.role));
                    StringBuilder rolesJson = new StringBuilder("[");
                    for (int r = 0; r < roles.size(); r++) {
                        if (r > 0) rolesJson.append(',');
                        rolesJson.append('"').append(jsonEscape(roles.get(r))).append('"');
                    }
                    rolesJson.append(']');
                    usersJson.append('{')
                            .append("\"username\":\"").append(jsonEscape(u.username)).append("\",")
                            .append("\"firstName\":\"").append(jsonEscape(u.firstName)).append("\",")
                            .append("\"lastName\":\"").append(jsonEscape(u.lastName)).append("\",")
                            .append("\"email\":\"").append(jsonEscape(u.email)).append("\",")
                            .append("\"role\":\"").append(jsonEscape(u.role)).append("\",")
                            .append("\"roles\":").append(rolesJson).append(',')
                            .append("\"enabled\":").append(u.enabled ? "true" : "false").append(',')
                            .append("\"isAdmin\":").append(hasAdminRole(u.role) ? "true" : "false")
                            .append('}');
                }
                usersJson.append(']');
                StringBuilder venuesJson = new StringBuilder("[");
                for (int i = 0; i < navOptions.size(); i++) {
                    NavOption o = navOptions.get(i);
                    if (i > 0) venuesJson.append(',');
                    venuesJson.append("{\"id\":").append(o.id).append(",\"label\":\"").append(jsonEscape(o.label)).append("\"}");
                }
                venuesJson.append(']');
                sendJson(exchange, 200, "{\"users\":" + usersJson + ",\"venues\":" + venuesJson
                        + ",\"canManage\":" + (canManage ? "true" : "false") + "}");
                return;
            }

            if (!canManage) {
                sendJsonMessage(exchange, 403, "You do not have permission to modify users.");
                return;
            }

            String body = readBody(exchange);
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String firstName = nullToEmpty(jsonGetString(body, "firstName")).trim();
                String lastName = nullToEmpty(jsonGetString(body, "lastName")).trim();
                String email = nullToEmpty(jsonGetString(body, "email")).trim();
                if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty()) {
                    sendJsonMessage(exchange, 400, "First name, last name, and email are required.");
                    return;
                }
                String newUsername = email.toLowerCase(Locale.ROOT);
                try {
                    saveUser(firstName, lastName, newUsername, email, DEFAULT_NEW_USER_PASSWORD, false, true);
                    sendJson(exchange, 200, "{\"message\":\"User created. Email is the username and default password is "
                            + jsonEscape(DEFAULT_NEW_USER_PASSWORD) + ".\"}");
                } catch (SQLException ex) {
                    if (ex.getMessage() != null && ex.getMessage().contains("UNIQUE")) {
                        sendJsonMessage(exchange, 400, "A user with that email already exists.");
                    } else {
                        sendJsonMessage(exchange, 500, "Unable to create the user.");
                    }
                }
                return;
            }

            if ("PUT".equalsIgnoreCase(exchange.getRequestMethod()) || "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String action = nullToEmpty(jsonGetString(body, "action")).trim().toLowerCase(Locale.ROOT);
                String target = nullToEmpty(jsonGetString(body, "username")).trim();
                if (target.isEmpty()) {
                    sendJsonMessage(exchange, 400, "Username required");
                    return;
                }
                List<String> roles = normalizeRoles(jsonGetStringArray(body, "roles"));
                if (roles.isEmpty()) {
                    String roleCsv = nullToEmpty(jsonGetString(body, "role"));
                    roles = normalizeRoles(parseRoles(roleCsv));
                }
                switch (action) {
                    case "delete" -> {
                        if (target.equals(currentUsername)) {
                            sendJsonMessage(exchange, 400, "You cannot delete your own account.");
                            return;
                        }
                        deleteUser(target);
                        sendJsonMessage(exchange, 200, "User deleted");
                    }
                    case "toggle-enabled" -> {
                        if (target.equals(currentUsername)) {
                            sendJsonMessage(exchange, 400, "You cannot disable your own account.");
                            return;
                        }
                        if (DEFAULT_ADMIN_USERNAME.equals(target)) {
                            sendJsonMessage(exchange, 400, "The default admin account cannot be disabled.");
                            return;
                        }
                        UserEntry targetUser = findUserEntryByUsername(target);
                        if (targetUser == null) {
                            sendJsonMessage(exchange, 404, "User not found.");
                            return;
                        }
                        boolean newEnabled = !targetUser.enabled;
                        setUserEnabled(target, newEnabled);
                        if (!newEnabled) {
                            destroySessionsForUser(target);
                        }
                        sendJsonMessage(exchange, 200, "User status updated");
                    }
                    case "update-role" -> {
                        if (!isValidRoles(roles, navOptions)) {
                            sendJsonMessage(exchange, 400, "Invalid role selected.");
                            return;
                        }
                        updateUserRole(target, serializeRoles(roles));
                        sendJsonMessage(exchange, 200, "Roles updated");
                    }
                    case "update" -> {
                        String firstName = nullToEmpty(jsonGetString(body, "firstName")).trim();
                        String lastName = nullToEmpty(jsonGetString(body, "lastName")).trim();
                        String email = nullToEmpty(jsonGetString(body, "email")).trim();
                        if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty()) {
                            sendJsonMessage(exchange, 400, "All fields are required.");
                            return;
                        }
                        if (!isValidRoles(roles, navOptions)) {
                            sendJsonMessage(exchange, 400, "Invalid role selected.");
                            return;
                        }
                        boolean updated = updateUserDetails(target, firstName, lastName, email, serializeRoles(roles));
                        if (!updated) {
                            sendJsonMessage(exchange, 500, "No changes were applied.");
                            return;
                        }
                        sendJsonMessage(exchange, 200, "User updated");
                    }
                    default -> sendJsonMessage(exchange, 400, "Unknown action");
                }
                return;
            }
            sendJsonMessage(exchange, 405, "Method not allowed");
        } catch (SQLException ex) {
            sendJsonMessage(exchange, 500, "Unable to process users request");
        }
    }

    private static void apiAdmin(HttpExchange exchange) throws IOException {
        String username = getSessionUsername(exchange);
        if (username == null) {
            sendJsonMessage(exchange, 401, "Unauthorized");
            return;
        }
        try {
            UserRecord user = findUserByUsername(username);
            if (user == null || !hasAdminRole(user.role)) {
                sendJsonMessage(exchange, 403, "Forbidden");
                return;
            }
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                java.util.List<NavOption> venues = listNavOptions();
                java.util.List<WorkItemDef> workItems = listWorkItemDefs();
                java.util.List<StatusDef> statuses = listStatusDefs();
                StringBuilder v = new StringBuilder("[");
                for (int i = 0; i < venues.size(); i++) {
                    if (i > 0) v.append(',');
                    v.append("{\"id\":").append(venues.get(i).id).append(",\"label\":\"")
                            .append(jsonEscape(venues.get(i).label)).append("\"}");
                }
                v.append(']');
                StringBuilder w = new StringBuilder("[");
                for (int i = 0; i < workItems.size(); i++) {
                    WorkItemDef d = workItems.get(i);
                    if (i > 0) w.append(',');
                    w.append("{\"id\":").append(d.id).append(",\"name\":\"").append(jsonEscape(d.name))
                            .append("\",\"sortOrder\":").append(d.sortOrder).append('}');
                }
                w.append(']');
                StringBuilder s = new StringBuilder("[");
                for (int i = 0; i < statuses.size(); i++) {
                    StatusDef d = statuses.get(i);
                    if (i > 0) s.append(',');
                    s.append("{\"id\":").append(d.id).append(",\"label\":\"").append(jsonEscape(d.label))
                            .append("\",\"percent\":").append(d.percentValue)
                            .append(",\"sortOrder\":").append(d.sortOrder).append('}');
                }
                s.append(']');
                sendJson(exchange, 200, "{\"venues\":" + v + ",\"workItems\":" + w + ",\"statuses\":" + s + "}");
                return;
            }

            String body = readBody(exchange);
            String action = nullToEmpty(jsonGetString(body, "action")).trim().toLowerCase(Locale.ROOT);
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            if ("DELETE".equals(method) && action.isEmpty()) {
                action = nullToEmpty(jsonGetString(body, "action")).trim().toLowerCase(Locale.ROOT);
            }

            switch (action) {
                case "venue-add", "add" -> {
                    String label = nullToEmpty(jsonGetString(body, "label")).trim();
                    if (label.isEmpty()) {
                        sendJsonMessage(exchange, 400, "Venue name is required.");
                        return;
                    }
                    saveNavOption(label);
                    sendJsonMessage(exchange, 200, "Venue added");
                }
                case "venue-update", "update" -> {
                    Integer id = parseNavOptionId(String.valueOf(jsonGetInt(body, "id", -1)));
                    String label = nullToEmpty(jsonGetString(body, "label")).trim();
                    if (id == null || label.isEmpty()) {
                        sendJsonMessage(exchange, 400, "Invalid venue update.");
                        return;
                    }
                    updateNavOption(id, label);
                    sendJsonMessage(exchange, 200, "Venue updated");
                }
                case "venue-delete", "delete" -> {
                    Integer id = parseNavOptionId(String.valueOf(jsonGetInt(body, "id", -1)));
                    if (id == null) {
                        sendJsonMessage(exchange, 400, "Invalid venue.");
                        return;
                    }
                    deleteNavOption(id);
                    sendJsonMessage(exchange, 200, "Venue deleted");
                }
                case "work-item-add" -> {
                    String name = nullToEmpty(jsonGetString(body, "name")).trim();
                    if (name.isEmpty()) {
                        sendJsonMessage(exchange, 400, "Work item name is required.");
                        return;
                    }
                    insertWorkItemDef(name, nextWorkItemSortOrder());
                    seedWorkItemToAllNavOptions(name);
                    sendJsonMessage(exchange, 200, "Work item added");
                }
                case "work-item-update" -> {
                    Integer id = parseNavOptionId(String.valueOf(jsonGetInt(body, "id", -1)));
                    String name = nullToEmpty(jsonGetString(body, "name")).trim();
                    if (id == null || name.isEmpty()) {
                        sendJsonMessage(exchange, 400, "Invalid work item update.");
                        return;
                    }
                    WorkItemDef existing = findWorkItemDefById(id);
                    if (existing == null) {
                        sendJsonMessage(exchange, 404, "Work item not found.");
                        return;
                    }
                    updateWorkItemDef(id, name);
                    if (!existing.name.equals(name)) {
                        renameWorkItemAcrossOptions(existing.name, name);
                    }
                    sendJsonMessage(exchange, 200, "Work item updated");
                }
                case "work-item-delete" -> {
                    Integer id = parseNavOptionId(String.valueOf(jsonGetInt(body, "id", -1)));
                    if (id == null) {
                        sendJsonMessage(exchange, 400, "Invalid work item.");
                        return;
                    }
                    WorkItemDef existing = findWorkItemDefById(id);
                    if (existing != null) {
                        deleteWorkItemDef(id);
                        deleteWorkItemAcrossOptions(existing.name);
                    }
                    sendJsonMessage(exchange, 200, "Work item deleted");
                }
                case "status-add" -> {
                    String label = nullToEmpty(jsonGetString(body, "label")).trim();
                    int percent = jsonGetInt(body, "percent", 0);
                    if (label.isEmpty()) {
                        sendJsonMessage(exchange, 400, "Status label is required.");
                        return;
                    }
                    insertStatusDef(label, percent, nextStatusSortOrder());
                    sendJsonMessage(exchange, 200, "Status added");
                }
                case "status-update" -> {
                    Integer id = parseNavOptionId(String.valueOf(jsonGetInt(body, "id", -1)));
                    String label = nullToEmpty(jsonGetString(body, "label")).trim();
                    if (id == null || label.isEmpty()) {
                        sendJsonMessage(exchange, 400, "Invalid status update.");
                        return;
                    }
                    StatusDef existing = findStatusDefById(id);
                    if (existing == null) {
                        sendJsonMessage(exchange, 404, "Status not found.");
                        return;
                    }
                    int percent = jsonGetInt(body, "percent", existing.percentValue);
                    updateStatusDef(id, label, percent);
                    if (!existing.label.equals(label)) {
                        renameStatusAcrossOptions(existing.label, label);
                    }
                    sendJsonMessage(exchange, 200, "Status updated");
                }
                case "status-delete" -> {
                    Integer id = parseNavOptionId(String.valueOf(jsonGetInt(body, "id", -1)));
                    if (id == null) {
                        sendJsonMessage(exchange, 400, "Invalid status.");
                        return;
                    }
                    java.util.List<StatusDef> statuses = listStatusDefs();
                    if (statuses.size() <= 1) {
                        sendJsonMessage(exchange, 400, "At least one status option is required.");
                        return;
                    }
                    StatusDef existing = findStatusDefById(id);
                    if (existing != null) {
                        deleteStatusDef(id);
                    }
                    sendJsonMessage(exchange, 200, "Status deleted");
                }
                default -> sendJsonMessage(exchange, 400, "Unknown action");
            }
        } catch (SQLException ex) {
            sendJsonMessage(exchange, 500, "Unable to update admin settings.");
        }
    }

    private static String venueProgressJson(OptionProgress progress) {
        String color = progressColor(progress.overallPercent);
        StringBuilder items = new StringBuilder("[");
        for (int i = 0; i < progress.workItems.size(); i++) {
            WorkItem item = progress.workItems.get(i);
            if (i > 0) items.append(',');
            items.append("{\"name\":\"").append(jsonEscape(item.name))
                    .append("\",\"status\":\"").append(jsonEscape(item.status)).append("\"}");
        }
        items.append(']');
        return "{\"id\":" + progress.option.id
                + ",\"label\":\"" + jsonEscape(progress.option.label) + "\""
                + ",\"percent\":" + progress.overallPercent
                + ",\"color\":\"" + jsonEscape(color) + "\""
                + ",\"workItems\":" + items + "}";
    }

    private static void apiStatus(HttpExchange exchange) throws IOException {
        String username = getSessionUsername(exchange);
        if (username == null) {
            sendJsonMessage(exchange, 401, "Unauthorized");
            return;
        }
        try {
            UserRecord user = findUserByUsername(username);
            if (user == null || !hasAdminRole(user.role)) {
                sendJsonMessage(exchange, 403, "Forbidden");
                return;
            }
            java.util.List<OptionProgress> progressList = listOptionProgress();
            Integer selectedId = null;
            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                for (String part : query.split("&")) {
                    String[] kv = part.split("=", 2);
                    if (kv.length == 2 && "optionId".equals(urlDecode(kv[0]))) {
                        selectedId = parseNavOptionId(urlDecode(kv[1]));
                    }
                }
            }
            OptionProgress selected = null;
            StringBuilder venues = new StringBuilder("[");
            for (int i = 0; i < progressList.size(); i++) {
                OptionProgress p = progressList.get(i);
                if (i > 0) venues.append(',');
                venues.append(venueProgressJson(p));
                if (selectedId != null && p.option.id == selectedId) {
                    selected = p;
                }
            }
            venues.append(']');
            if (selected == null && !progressList.isEmpty()) {
                selected = progressList.get(0);
            }
            String selectedJson = selected == null ? "null" : venueProgressJson(selected);
            sendJson(exchange, 200, "{\"venues\":" + venues + ",\"selected\":" + selectedJson + "}");
        } catch (SQLException ex) {
            sendJsonMessage(exchange, 500, "Unable to load status");
        }
    }

    private static void apiStatusExport(HttpExchange exchange) throws IOException {
        handleStatusExport(exchange);
    }

    private static void apiMapview(HttpExchange exchange) throws IOException {
        String username = getSessionUsername(exchange);
        if (username == null) {
            sendJsonMessage(exchange, 401, "Unauthorized");
            return;
        }
        try {
            UserRecord user = findUserByUsername(username);
            if (user == null || !user.enabled) {
                sendJsonMessage(exchange, 401, "Unauthorized");
                return;
            }
            java.util.List<OptionProgress> progressList = listOptionProgress();
            java.util.Map<Integer, Integer> markersPerCity = new java.util.HashMap<>();
            java.util.List<double[]> placedPoints = new java.util.ArrayList<>();
            StringBuilder markers = new StringBuilder("[");
            StringBuilder venues = new StringBuilder("[");
            boolean firstMarker = true;
            boolean firstVenue = true;
            for (OptionProgress progress : progressList) {
                String color = progressColor(progress.overallPercent);
                if (!firstVenue) venues.append(',');
                firstVenue = false;
                venues.append(venueProgressJson(progress));

                int cityIndex = resolveCityIndex(progress.option.label);
                if (cityIndex < 0) {
                    continue;
                }
                int slot = markersPerCity.merge(cityIndex, 1, Integer::sum) - 1;
                double[] xy = projectSaudiMap(CITY_COORDS[cityIndex][0], CITY_COORDS[cityIndex][1]);
                xy = offsetMapPoint(xy[0], xy[1], slot);
                for (int pass = 0; pass < 4; pass++) {
                    boolean moved = false;
                    for (double[] other : placedPoints) {
                        double dx = xy[0] - other[0];
                        double dy = xy[1] - other[1];
                        double dist = Math.sqrt(dx * dx + dy * dy);
                        if (dist < 46 && dist > 0.01) {
                            double push = (46 - dist) / 2.0;
                            xy[0] += (dx / dist) * push;
                            xy[1] += (dy / dist) * push;
                            moved = true;
                        }
                    }
                    if (!moved) break;
                }
                placedPoints.add(new double[]{xy[0], xy[1]});
                if (!firstMarker) markers.append(',');
                firstMarker = false;
                markers.append("{\"id\":").append(progress.option.id)
                        .append(",\"label\":\"").append(jsonEscape(progress.option.label)).append("\"")
                        .append(",\"x\":").append(String.format(Locale.US, "%.1f", xy[0]))
                        .append(",\"y\":").append(String.format(Locale.US, "%.1f", xy[1]))
                        .append(",\"percent\":").append(progress.overallPercent)
                        .append(",\"color\":\"").append(jsonEscape(color)).append("\"}");
            }
            markers.append(']');
            venues.append(']');
            String viewBox = "0 0 " + ((int) MAP_WIDTH) + " " + ((int) MAP_HEIGHT);
            sendJson(exchange, 200, "{"
                    + "\"viewBox\":\"" + viewBox + "\","
                    + "\"landPath\":\"" + jsonEscape(saudiOutlinePath()) + "\","
                    + "\"markers\":" + markers + ","
                    + "\"venues\":" + venues
                    + "}");
        } catch (SQLException ex) {
            sendJsonMessage(exchange, 500, "Unable to load map view");
        }
    }
}
