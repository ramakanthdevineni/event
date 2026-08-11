package com.example;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
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
    private static final String SESSION_COOKIE_NAME = "SESSIONID";
    private static final String DEFAULT_NEW_USER_PASSWORD = "Match123$";
    private static final ConcurrentMap<String, String> sessions = new ConcurrentHashMap<>();

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
        createDefaultAdminUser();
        // ensure role column reflects existing is_admin flags
        try (Connection connection = DriverManager.getConnection(DB_URL);
             Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("UPDATE users SET role = 'admin' WHERE is_admin = 1");
        } catch (SQLException ex) {
            // ignore migration failures
        }
    }

    private static void ensureUserTableColumns(Connection connection) throws SQLException {
        addColumnIfMissing(connection, "users", "is_admin", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "users", "must_change_password", "INTEGER NOT NULL DEFAULT 0");
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
            sendHtmlResponse(exchange, 400, buildErrorPage("Please provide both username and password."));
            return;
        }

        try {
            UserRecord user = findUserByCredentials(username, password);
            if (user == null) {
                sendHtmlResponse(exchange, 401, buildErrorPage("Invalid username or password. Please try again."));
            } else {
                String sessionId = createSession(username);
                exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE_NAME + "=" + sessionId + "; Path=/; HttpOnly");
                if (user.mustChangePassword) {
                    redirect(exchange, "/change-password");
                } else {
                    redirect(exchange, "/dashboard");
                }
            }
        } catch (SQLException ex) {
            sendHtmlResponse(exchange, 500, buildErrorPage("Unable to verify credentials right now. Please try again later."));
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
            sendHtmlResponse(exchange, 200, buildDashboardPage(user.firstName, username, user.isAdmin, listNavOptions()));
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

            sendHtmlResponse(exchange, 200, buildAdminPanelPage(username, adminUser.firstName, "", listNavOptions(), null));
        } catch (SQLException ex) {
            sendHtmlResponse(exchange, 500, buildErrorPage("Unable to load the admin panel right now."));
        }
    }

    private static void handleAdminPanelPost(HttpExchange exchange, String username, String firstName) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> formData = parseFormData(requestBody);
        String label = formData.getOrDefault("label", "").trim();

        try {
            java.util.List<NavOption> navOptions = listNavOptions();
            if (label.isEmpty()) {
                sendHtmlResponse(exchange, 400, buildAdminPanelPage(username, firstName, label, navOptions, "Please enter a name for the navigation option."));
                return;
            }

            saveNavOption(label);
            sendHtmlResponse(exchange, 200, buildAdminPanelPage(username, firstName, "", listNavOptions(), "Navigation option \"" + label + "\" added successfully."));
        } catch (SQLException ex) {
            String message = ex.getMessage();
            if (message != null && message.contains("UNIQUE")) {
                sendHtmlResponse(exchange, 400, buildAdminPanelPage(username, firstName, label, listNavOptions(), "A navigation option with that name already exists."));
            } else {
                sendHtmlResponse(exchange, 500, buildAdminPanelPage(username, firstName, label, listNavOptions(), "Unable to add the navigation option. Please try again later."));
            }
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

            sendHtmlResponse(exchange, 200, buildCustomPage(user.firstName, username, user.isAdmin, option, listNavOptions()));
        } catch (SQLException ex) {
            sendHtmlResponse(exchange, 500, buildErrorPage("Unable to load the page right now."));
        }
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
                        editData.put("role", u.isAdmin || "admin".equalsIgnoreCase(u.role) ? "admin" : "user");
                        break;
                    }
                }
            }

            sendHtmlResponse(exchange, 200, buildUsersPage(users, editData, current.isAdmin, currentUsername, current.firstName, null, listNavOptions()));
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
        String username = form.getOrDefault("username", "").trim();
        String firstName = form.getOrDefault("firstName", "").trim();
        String lastName = form.getOrDefault("lastName", "").trim();
        String email = form.getOrDefault("email", "").trim();
        String role = form.getOrDefault("role", "user");

        if (username.isEmpty() || firstName.isEmpty() || lastName.isEmpty() || email.isEmpty()) {
            try {
                var users = listAllUsers();
                String curUser = getSessionUsername(exchange);
                String curFirst = "";
                try { curFirst = findFirstNameByUsername(curUser); } catch (SQLException e) { curFirst = ""; }
                sendHtmlResponse(exchange, 400, buildUsersPage(users, form, true, curUser, curFirst, "All fields are required.", listNavOptions()));
            } catch (SQLException ex) {
                sendHtmlResponse(exchange, 500, buildErrorPage("Unable to update user."));
            }
            return;
        }

        try {
            boolean updated = updateUserDetails(username, firstName, lastName, email, "admin".equalsIgnoreCase(role));
            if (updated) {
                // Redirect on success to avoid confusing 400/200 behavior
                redirect(exchange, "/users?edit=" + java.net.URLEncoder.encode(username, StandardCharsets.UTF_8));
                return;
            } else {
                // No rows updated — show error to user
                var users = listAllUsers();
                String curUser = getSessionUsername(exchange);
                String curFirst = "";
                try { curFirst = findFirstNameByUsername(curUser); } catch (SQLException e) { curFirst = ""; }
                sendHtmlResponse(exchange, 500, buildUsersPage(users, form, true, curUser, curFirst, "No changes were applied.", listNavOptions()));
                return;
            }
        } catch (SQLException ex) {
            String msg = ex.getMessage();
            try {
                var users = listAllUsers();
                String feedback = (msg != null && msg.contains("UNIQUE")) ? "Email already in use." : "Unable to save changes.";
                String curUser = getSessionUsername(exchange);
                String curFirst = "";
                try { curFirst = findFirstNameByUsername(curUser); } catch (SQLException e) { curFirst = ""; }
                sendHtmlResponse(exchange, 500, buildUsersPage(users, form, true, curUser, curFirst, feedback, listNavOptions()));
            } catch (SQLException inner) {
                sendHtmlResponse(exchange, 500, buildErrorPage("Unable to save changes."));
            }
        }
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

    private static String buildSidebarHtml(String username, boolean isAdmin, java.util.List<NavOption> navOptions, String navItemClass) {
        StringBuilder nav = new StringBuilder();
        nav.append("<a class=\"").append(navItemClass).append("\" href=\"/dashboard\">Dashboard</a>");
        nav.append("<a class=\"").append(navItemClass).append("\" href=\"/users\">Users</a>");
        if (isAdmin) {
            nav.append("<a class=\"").append(navItemClass).append("\" href=\"/admin-panel\">Admin Panel</a>");
        }
        for (NavOption option : navOptions) {
            nav.append("<a class=\"").append(navItemClass).append("\" href=\"/page?id=").append(option.id).append("\">")
                    .append(escapeHtml(option.label)).append("</a>");
        }
        return "<aside class=\"sidebar\"><h2>Navigation</h2><nav>" + nav + "</nav><hr/>" +
                "<p style=\"opacity:0.8;font-size:0.9rem;\">Logged in as " + escapeHtml(username) + "</p></aside>";
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

        UserEntry(String username, String firstName, String lastName, String email, boolean isAdmin, String role) {
            this.username = username;
            this.firstName = firstName;
            this.lastName = lastName;
            this.email = email;
            this.isAdmin = isAdmin;
            this.role = role == null ? "user" : role;
        }
    }

    private static java.util.List<UserEntry> listAllUsers() throws SQLException {
        String sql = "SELECT username, first_name, last_name, email, is_admin, role FROM users ORDER BY created_at DESC";
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
                        rs.getString("role")
                ));
            }
        }
        return list;
    }

    private static boolean updateUserDetails(String username, String firstName, String lastName, String email, boolean isAdmin) throws SQLException {
        String role = isAdmin ? "admin" : "user";
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

    private static String buildUsersPage(java.util.List<UserEntry> users, Map<String, String> editData, boolean currentIsAdmin, String currentUsername, String currentFirstName, String message, java.util.List<NavOption> navOptions) {
        String selectedUsername = editData == null ? null : editData.get("username");

        StringBuilder tableRows = new StringBuilder();
        for (UserEntry u : users) {
            String fullName = escapeHtml(u.firstName) + " " + escapeHtml(u.lastName);
            String email = escapeHtml(u.email);
            String roleSelected = u.isAdmin || "admin".equalsIgnoreCase(u.role) ? "admin" : "user";
            boolean isSelected = u.username.equals(selectedUsername);

            String roleCell;
            if (currentIsAdmin) {
                roleCell = "<form class=\"role-form\" action=\"/users\" method=\"post\">" +
                        "<input type=\"hidden\" name=\"username\" value=\"" + escapeHtml(u.username) + "\"/>" +
                        "<input type=\"hidden\" name=\"firstName\" value=\"" + escapeHtml(u.firstName) + "\"/>" +
                        "<input type=\"hidden\" name=\"lastName\" value=\"" + escapeHtml(u.lastName) + "\"/>" +
                        "<input type=\"hidden\" name=\"email\" value=\"" + escapeHtml(u.email) + "\"/>" +
                        "<select name=\"role\">" +
                        "<option value=\"user\"" + ("user".equals(roleSelected) ? " selected" : "") + ">User</option>" +
                        "<option value=\"admin\"" + ("admin".equals(roleSelected) ? " selected" : "") + ">Admin</option>" +
                        "</select>" +
                        "<button type=\"submit\" class=\"btn-sm\">Save</button>" +
                        "</form>";
            } else {
                roleCell = "<select disabled><option selected>" + ("admin".equals(roleSelected) ? "Admin" : "User") + "</option></select>";
            }

            String actionsCell = currentIsAdmin
                    ? "<a class=\"button btn-sm\" href=\"/users?edit=" + java.net.URLEncoder.encode(u.username, StandardCharsets.UTF_8) + "\">Edit</a>"
                    : "";

            tableRows.append("<tr").append(isSelected ? " class=\"selected\"" : "").append(">")
                    .append("<td class=\"name-cell\">").append(fullName).append("</td>")
                    .append("<td class=\"email-cell\">").append(email).append("</td>")
                    .append("<td class=\"role-cell\">").append(roleCell).append("</td>");
            if (currentIsAdmin) {
                tableRows.append("<td class=\"actions-cell\">").append(actionsCell).append("</td>");
            }
            tableRows.append("</tr>");
        }

        String actionsHeader = currentIsAdmin ? "<th>Actions</th>" : "";
        String userListHtml = "<div class=\"users-panel\"><table class=\"users-table\"><thead><tr>" +
                "<th>Name</th><th>Email</th><th>Role</th>" + actionsHeader +
                "</tr></thead><tbody>" + tableRows + "</tbody></table></div>";

        String right;
        if (editData == null) {
            right = "<div class=\"placeholder\"><p>No user selected.</p>" +
                    (currentIsAdmin ? "<p style=\"opacity:0.75;font-size:0.9rem;\">Select Edit on a user to update their profile.</p>" : "") +
                    "</div>";
        } else if (!currentIsAdmin) {
            String fn = escapeHtml(editData.getOrDefault("firstName", ""));
            String ln = escapeHtml(editData.getOrDefault("lastName", ""));
            String em = escapeHtml(editData.getOrDefault("email", ""));
            String roleVal = editData.getOrDefault("role", editData.getOrDefault("isAdmin", "0"));
            String displayRole = ("1".equals(roleVal) || "admin".equalsIgnoreCase(roleVal)) ? "Admin" : "User";
            right = "<div class=\"read-only-details\"><h3 style=\"margin:0 0 1rem;\">" + fn + " " + ln + "</h3>" +
                    "<p><strong>Email:</strong> " + em + "</p>" +
                    "<p><strong>Role:</strong> " + displayRole + "</p>" +
                    "<p style=\"opacity:0.75;margin-top:1.5rem;\">You do not have permission to edit user profiles.</p></div>";
        } else {
            String uname = escapeHtml(editData.getOrDefault("username", ""));
            String fn = escapeHtml(editData.getOrDefault("firstName", ""));
            String ln = escapeHtml(editData.getOrDefault("lastName", ""));
            String em = escapeHtml(editData.getOrDefault("email", ""));
            String roleVal = editData.getOrDefault("role", editData.getOrDefault("isAdmin", "0"));
            String roleSelected = "user";
            if ("1".equals(roleVal) || "admin".equalsIgnoreCase(roleVal)) {
                roleSelected = "admin";
            }
            String roleControl = "<label for=\"role\">Role</label><select id=\"role\" name=\"role\">" +
                    "<option value=\"user\"" + ("user".equals(roleSelected) ? " selected" : "") + ">User</option>" +
                    "<option value=\"admin\"" + ("admin".equals(roleSelected) ? " selected" : "") + ">Admin</option></select>";
            String feedback = message == null ? "" : "<p style=\"color:#a5f3fc;font-weight:600;\">" + escapeHtml(message) + "</p>";

            right = "<form class=\"edit-form\" action=\"/users\" method=\"post\">" +
                    "<h3 style=\"margin:0 0 1rem;\">Edit User</h3>" +
                    "<input type=\"hidden\" name=\"username\" value=\"" + uname + "\"/>" +
                    "<label for=\"firstName\">First Name</label><input id=\"firstName\" name=\"firstName\" type=\"text\" value=\"" + fn + "\" required/>" +
                    "<label for=\"lastName\">Last Name</label><input id=\"lastName\" name=\"lastName\" type=\"text\" value=\"" + ln + "\" required/>" +
                    "<label for=\"email\">Email</label><input id=\"email\" name=\"email\" type=\"email\" value=\"" + em + "\" required/>" +
                    roleControl +
                    "<div class=\"form-actions\"><button type=\"submit\">Save Changes</button><a href=\"/users\">Cancel</a></div>" +
                    feedback +
                    "</form>";
        }

        String feedback = message == null ? "" : "<div class=\"page-feedback\">" + escapeHtml(message) + "</div>";

        String headerHtml = "<div style=\"display:flex;align-items:center;justify-content:space-between;margin-bottom:1rem;\">" +
                "<div><h2 style=\"margin:0 0 0.25rem;\">Welcome, " + escapeHtml(currentFirstName) + "</h2><p style=\"margin:0;opacity:0.9;\">Signed in as " + escapeHtml(currentUsername) + "</p></div>" +
                "<div style=\"display:flex;gap:0.75rem;align-items:center;\">" +
                "<a class=\"button\" href=\"/profile\">Edit Profile</a>" +
                "<a class=\"button\" href=\"/logout\">Logout</a>" +
                (currentIsAdmin ? "<a class=\"button\" href=\"/add-user\">" +
                        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" style=\"vertical-align:middle;\"><path d=\"M12 5v14M5 12h14\"></path></svg> <span style=\"margin-left:0.45rem;vertical-align:middle;\">Add User</span></a>" : "") +
                "</div></div>";

        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>Users</title><style>" +
                "body{margin:0;font-family:Arial,Helvetica,sans-serif;background:linear-gradient(135deg,#0f172a,#2563eb);color:#f8fafc;}" +
                ".container{display:flex;min-height:100vh;}" +
                ".sidebar{width:260px;padding:1.5rem;background:rgba(255,255,255,0.04);border-right:1px solid rgba(255,255,255,0.04);}" +
                ".user-item{display:block;padding:0.6rem;border-radius:10px;color:#e6eef8;text-decoration:none;margin-bottom:0.35rem;}" +
                ".user-item:hover{background:rgba(255,255,255,0.03);}" +
                ".main{flex:1;padding:2rem;}" +
                ".users-layout{display:flex;gap:2rem;align-items:flex-start;}" +
                ".users-list{flex:2;min-width:0;}" +
                ".users-detail{flex:1;min-width:280px;padding:1.5rem;border-radius:16px;background:rgba(255,255,255,0.06);}" +
                ".users-panel{border-radius:16px;overflow:hidden;background:rgba(255,255,255,0.06);box-shadow:0 12px 30px rgba(15,23,42,0.2);}" +
                ".users-table{width:100%;border-collapse:collapse;}" +
                ".users-table th,.users-table td{padding:0.9rem 1rem;text-align:left;border-bottom:1px solid rgba(255,255,255,0.08);vertical-align:middle;}" +
                ".users-table th{font-size:0.8rem;text-transform:uppercase;letter-spacing:0.06em;opacity:0.7;font-weight:600;}" +
                ".users-table tbody tr:hover{background:rgba(255,255,255,0.03);}" +
                ".users-table tr.selected{background:rgba(255,255,255,0.08);}" +
                ".name-cell{font-weight:600;}" +
                ".email-cell{opacity:0.9;font-size:0.95rem;}" +
                ".role-form{display:flex;gap:0.5rem;align-items:center;margin:0;}" +
                ".placeholder{opacity:0.85;}" +
                ".page-feedback{padding:0.5rem 0;color:#a5f3fc;font-weight:600;}" +
                ".edit-form{display:grid;gap:0.75rem;}" +
                ".form-actions{display:flex;gap:0.75rem;align-items:center;margin-top:0.5rem;}" +
                "label{font-size:0.95rem;opacity:0.9;}" +
                "input{padding:0.8rem;border-radius:10px;border:1px solid rgba(255,255,255,0.12);background:rgba(255,255,255,0.06);color:#fff;}" +
                "select{padding:0.55rem 2rem 0.55rem 0.75rem;border-radius:10px;border:1px solid rgba(255,255,255,0.25);background:#1e293b;color:#f8fafc;font-size:0.95rem;cursor:pointer;appearance:auto;}" +
                "select option{background:#fff;color:#0f172a;}" +
                "select option:checked,select option:hover{background:#2563eb;color:#fff;}" +
                "select:disabled{opacity:0.85;cursor:not-allowed;background:rgba(255,255,255,0.08);}" +
                "button{padding:0.7rem 1rem;border-radius:10px;border:none;background:#2563eb;color:#fff;font-weight:600;cursor:pointer;}" +
                "button:hover{background:#1d4ed8;}" +
                ".btn-sm{padding:0.45rem 0.75rem;font-size:0.85rem;}" +
                "a{color:#cbd5e1;text-decoration:none;}" +
                "a.button{padding:0.6rem 0.9rem;border-radius:10px;background:#2563eb;color:#fff;text-decoration:none;font-weight:600;display:inline-block;}" +
                "a.button:hover{background:#1d4ed8;}" +
                "</style></head><body>" +
                "<div class=\"container\">" + buildSidebarHtml(currentUsername, currentIsAdmin, navOptions, "user-item") + "<main class=\"main\">" +
                headerHtml + feedback +
                "<div class=\"users-layout\"><div class=\"users-list\">" + userListHtml + "</div><div class=\"users-detail\">" + right + "</div></div>" +
                "</main></div></body></html>";
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
        sessions.put(sessionId, username);
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
        return sessionId != null ? sessions.get(sessionId) : null;
    }

    private static UserRecord findUserByUsername(String username) throws SQLException {
        String sql = "SELECT first_name, is_admin, must_change_password FROM users WHERE username = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (var rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new UserRecord(
                        rs.getString("first_name"),
                        rs.getInt("is_admin") == 1,
                        rs.getInt("must_change_password") == 1
                );
            }
        }
    }

    private static UserRecord findUserByCredentials(String username, String password) throws SQLException {
        String sql = "SELECT first_name, is_admin, must_change_password FROM users WHERE username = ? AND password = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, password);
            try (var rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new UserRecord(
                        rs.getString("first_name"),
                        rs.getInt("is_admin") == 1,
                        rs.getInt("must_change_password") == 1
                );
            }
        }
    }

    private static class UserRecord {
        final String firstName;
        final boolean isAdmin;
        final boolean mustChangePassword;

        UserRecord(String firstName, boolean isAdmin, boolean mustChangePassword) {
            this.firstName = firstName;
            this.isAdmin = isAdmin;
            this.mustChangePassword = mustChangePassword;
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

    private static String buildDashboardPage(String firstName, String username, boolean isAdmin, java.util.List<NavOption> navOptions) {
        return "<!DOCTYPE html>" +
                "<html lang=\"en\">" +
                "<head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>Dashboard</title>" +
                "<style>" + sidebarLayoutStyles() + " .top-actions{display:flex;justify-content:flex-end;gap:0.75rem;margin-bottom:1.5rem;} .card{max-width:720px;padding:2rem;border-radius:20px;background:rgba(255,255,255,0.06);box-shadow:0 20px 45px rgba(15,23,42,0.25);backdrop-filter:blur(6px);} </style></head><body>" +
                "<div class=\"container\">" + buildSidebarHtml(username, isAdmin, navOptions, "nav-item") + "<main class=\"main\">" +
                "<div class=\"top-actions\"><a class=\"button\" href=\"/profile\">Edit Profile</a><a class=\"button\" href=\"/logout\">Logout</a></div>" +
                "<div class=\"card\"><h1 style=\"margin:0 0 0.5rem;\">Welcome, " + escapeHtml(firstName) + "</h1>" +
                "<p style=\"margin:0;opacity:0.9;\">Your username is " + escapeHtml(username) + ".</p>" +
                "</div></main></div></body></html>";
    }

    private static String buildAdminPanelPage(String username, String firstName, String labelValue, java.util.List<NavOption> navOptions, String message) {
        String feedback = message == null ? "" : "<p style=\"color:#a5f3fc;margin-top:1rem;font-weight:600;\">" + escapeHtml(message) + "</p>";
        StringBuilder existingOptions = new StringBuilder();
        if (navOptions.isEmpty()) {
            existingOptions.append("<p style=\"opacity:0.85;\">No custom navigation options yet.</p>");
        } else {
            existingOptions.append("<ul style=\"margin:0;padding-left:1.25rem;\">");
            for (NavOption option : navOptions) {
                existingOptions.append("<li style=\"margin-bottom:0.35rem;\">")
                        .append(escapeHtml(option.label))
                        .append(" <span style=\"opacity:0.7;font-size:0.9rem;\">(visible in sidebar)</span></li>");
            }
            existingOptions.append("</ul>");
        }

        return "<!DOCTYPE html>" +
                "<html lang=\"en\">" +
                "<head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>Admin Panel</title>" +
                "<style>" + sidebarLayoutStyles() +
                " .top-actions{display:flex;justify-content:flex-end;gap:0.75rem;margin-bottom:1.5rem;}" +
                " .card{max-width:720px;padding:2rem;border-radius:20px;background:rgba(255,255,255,0.06);box-shadow:0 20px 45px rgba(15,23,42,0.25);backdrop-filter:blur(6px);}" +
                " form{display:grid;gap:1rem;margin-top:1rem;}" +
                " label{font-size:0.95rem;opacity:0.9;}" +
                " input{padding:0.8rem;border-radius:10px;border:1px solid rgba(255,255,255,0.12);background:rgba(255,255,255,0.06);color:#fff;}" +
                " button{padding:0.7rem 1rem;border-radius:10px;border:none;background:#2563eb;color:#fff;font-weight:600;cursor:pointer;}" +
                " button:hover{background:#1d4ed8;}" +
                "</style></head><body>" +
                "<div class=\"container\">" + buildSidebarHtml(username, true, navOptions, "nav-item") + "<main class=\"main\">" +
                "<div class=\"top-actions\"><a class=\"button\" href=\"/profile\">Edit Profile</a><a class=\"button\" href=\"/logout\">Logout</a></div>" +
                "<div class=\"card\"><h1 style=\"margin:0 0 0.5rem;\">Admin Panel</h1>" +
                "<p style=\"margin:0;opacity:0.9;\">Add a new option to the left navigation. It will appear as a link for all logged-in users.</p>" +
                feedback +
                "<form action=\"/admin-panel\" method=\"post\">" +
                "<label for=\"label\">Navigation Option Name</label>" +
                "<input id=\"label\" name=\"label\" type=\"text\" value=\"" + escapeHtml(labelValue) + "\" placeholder=\"Enter option name\" required/>" +
                "<button type=\"submit\">Add Option</button>" +
                "</form>" +
                "<div style=\"margin-top:2rem;\"><h2 style=\"margin:0 0 0.75rem;font-size:1.1rem;\">Current Navigation Options</h2>" +
                existingOptions +
                "</div></div></main></div></body></html>";
    }

    private static String buildCustomPage(String firstName, String username, boolean isAdmin, NavOption option, java.util.List<NavOption> navOptions) {
        return "<!DOCTYPE html>" +
                "<html lang=\"en\">" +
                "<head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>" + escapeHtml(option.label) + "</title>" +
                "<style>" + sidebarLayoutStyles() +
                " .top-actions{display:flex;justify-content:flex-end;gap:0.75rem;margin-bottom:1.5rem;}" +
                " .card{max-width:720px;padding:2rem;border-radius:20px;background:rgba(255,255,255,0.06);box-shadow:0 20px 45px rgba(15,23,42,0.25);backdrop-filter:blur(6px);}" +
                "</style></head><body>" +
                "<div class=\"container\">" + buildSidebarHtml(username, isAdmin, navOptions, "nav-item") + "<main class=\"main\">" +
                "<div class=\"top-actions\"><a class=\"button\" href=\"/profile\">Edit Profile</a><a class=\"button\" href=\"/logout\">Logout</a></div>" +
                "<div class=\"card\"><h1 style=\"margin:0;\">Welcome to " + escapeHtml(option.label) + "</h1></div>" +
                "</main></div></body></html>";
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
