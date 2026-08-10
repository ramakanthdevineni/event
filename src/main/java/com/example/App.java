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
        }
        createDefaultAdminUser();
    }

    private static void ensureUserTableColumns(Connection connection) throws SQLException {
        addColumnIfMissing(connection, "users", "is_admin", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "users", "must_change_password", "INTEGER NOT NULL DEFAULT 0");
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
            sendHtmlResponse(exchange, 200, buildDashboardPage(user.firstName, username, user.isAdmin));
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
                        break;
                    }
                }
            }

            sendHtmlResponse(exchange, 200, buildUsersPage(users, editData, current.isAdmin, currentUsername, current.firstName, null));
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
                sendHtmlResponse(exchange, 400, buildUsersPage(users, form, true, curUser, curFirst, "All fields are required."));
            } catch (SQLException ex) {
                sendHtmlResponse(exchange, 500, buildErrorPage("Unable to update user."));
            }
            return;
        }

        try {
            updateUserDetails(username, firstName, lastName, email, "admin".equalsIgnoreCase(role));
            redirect(exchange, "/users?edit=" + java.net.URLEncoder.encode(username, StandardCharsets.UTF_8));
        } catch (SQLException ex) {
            String msg = ex.getMessage();
            try {
                var users = listAllUsers();
                String feedback = (msg != null && msg.contains("UNIQUE")) ? "Email already in use." : "Unable to save changes.";
                String curUser = getSessionUsername(exchange);
                String curFirst = "";
                try { curFirst = findFirstNameByUsername(curUser); } catch (SQLException e) { curFirst = ""; }
                sendHtmlResponse(exchange, 500, buildUsersPage(users, form, true, curUser, curFirst, feedback));
            } catch (SQLException inner) {
                sendHtmlResponse(exchange, 500, buildErrorPage("Unable to save changes."));
            }
        }
    }

    private static class UserEntry {
        final String username;
        final String firstName;
        final String lastName;
        final String email;
        final boolean isAdmin;

        UserEntry(String username, String firstName, String lastName, String email, boolean isAdmin) {
            this.username = username;
            this.firstName = firstName;
            this.lastName = lastName;
            this.email = email;
            this.isAdmin = isAdmin;
        }
    }

    private static java.util.List<UserEntry> listAllUsers() throws SQLException {
        String sql = "SELECT username, first_name, last_name, email, is_admin FROM users ORDER BY created_at DESC";
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
                        rs.getInt("is_admin") == 1
                ));
            }
        }
        return list;
    }

    private static void updateUserDetails(String username, String firstName, String lastName, String email, boolean isAdmin) throws SQLException {
        String sql = "UPDATE users SET first_name = ?, last_name = ?, email = ?, is_admin = ? WHERE username = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, firstName);
            stmt.setString(2, lastName);
            stmt.setString(3, email);
            stmt.setInt(4, isAdmin ? 1 : 0);
            stmt.setString(5, username);
            stmt.executeUpdate();
        }
    }

    private static String buildUsersPage(java.util.List<UserEntry> users, Map<String, String> editData, boolean currentIsAdmin, String currentUsername, String currentFirstName, String message) {
        StringBuilder left = new StringBuilder();
        for (UserEntry u : users) {
            left.append("<a class=\\\"user-item\\\" href=\\\"/users?edit=").append(java.net.URLEncoder.encode(u.username, StandardCharsets.UTF_8)).append("\\\">")
                    .append("<div><strong>").append(escapeHtml(u.firstName)).append(" ").append(escapeHtml(u.lastName)).append("</strong><br><small>").append(escapeHtml(u.username)).append("</small>")
                    .append(u.isAdmin ? " <span style=\\\"color:#fde68a;font-weight:600;\\\">(Admin)</span>" : "")
                    .append("</div></a>");
        }

        String right;
        if (editData == null) {
            right = "<div class=\"placeholder\">Select a user from the left to view details.</div>";
        } else {
            String uname = escapeHtml(editData.getOrDefault("username", ""));
            String fn = escapeHtml(editData.getOrDefault("firstName", ""));
            String ln = escapeHtml(editData.getOrDefault("lastName", ""));
            String em = escapeHtml(editData.getOrDefault("email", ""));
            String roleVal = editData.getOrDefault("isAdmin", "0");

            String roleControl;
            if (currentIsAdmin) {
                roleControl = "<label for=\"role\">Role</label><select id=\"role\" name=\"role\"><option value=\"user\"" + ("0".equals(roleVal) ? " selected" : "") + ">User</option><option value=\"admin\"" + ("1".equals(roleVal) ? " selected" : "") + ">Admin</option></select>";
            } else {
                roleControl = "<p><strong>Role:</strong> " + ("1".equals(roleVal) ? "Admin" : "User") + "</p>";
            }

            String disabled = currentIsAdmin ? "" : "disabled";

            String feedback = message == null ? "" : "<p style=\\\"color:#a5f3fc;font-weight:600;\\\">" + escapeHtml(message) + "</p>";

            right = "<form class=\\\"edit-form\\\" action=\\\"/users\\\" method=\\\"post\\\">" +
                    "<input type=\\\"hidden\\\" name=\\\"username\\\" value=\\\"" + uname + "\\\" />" +
                    "<label for=\\\"firstName\\\">First Name</label><input id=\\\"firstName\\\" name=\\\"firstName\\\" type=\\\"text\\\" value=\\\"" + fn + "\\\" required " + disabled + "/>" +
                    "<label for=\\\"lastName\\\">Last Name</label><input id=\\\"lastName\\\" name=\\\"lastName\\\" type=\\\"text\\\" value=\\\"" + ln + "\\\" required " + disabled + "/>" +
                    "<label for=\\\"email\\\">Email</label><input id=\\\"email\\\" name=\\\"email\\\" type=\\\"email\\\" value=\\\"" + em + "\\\" required " + disabled + "/>" +
                    roleControl +
                    "<div style=\\\"margin-top:1rem;\\\">" + (currentIsAdmin ? "<button type=\\\"submit\\\">Save Changes</button>" : "") + "<a href=\\\"/dashboard\\\" style=\\\"margin-left:0.75rem;color:#cbd5e1;text-decoration:none;\\\">Back</a></div>" +
                    feedback +
                    "</form>";
        }

        String feedback = message == null ? "" : "<div style=\"padding:0.5rem 0;color:#a5f3fc;font-weight:600;\">" + escapeHtml(message) + "</div>";

        // show welcome at top of right pane
        String welcomeHtml = "<div style=\"margin-bottom:1rem;\"><h2 style=\"margin:0 0 0.25rem;\">Welcome, " + escapeHtml(currentFirstName) + "</h2><p style=\"margin:0;opacity:0.9;\">Signed in as " + escapeHtml(currentUsername) + "</p></div>";

        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>Users</title><style>body{margin:0;font-family:Arial,Helvetica,sans-serif;background:linear-gradient(135deg,#0f172a,#2563eb);color:#f8fafc;} .container{display:flex;min-height:100vh;} .sidebar{width:260px;padding:1.5rem;background:rgba(255,255,255,0.04);border-right:1px solid rgba(255,255,255,0.04);} .user-item{display:block;padding:0.6rem;border-radius:10px;color:#e6eef8;text-decoration:none;margin-bottom:0.35rem;} .user-item:hover{background:rgba(255,255,255,0.03);} .main{flex:1;padding:2rem;} .placeholder{opacity:0.85;} .edit-form{max-width:560px;display:grid;gap:0.75rem;} label{font-size:0.95rem;opacity:0.9;} input, select{padding:0.8rem;border-radius:10px;border:1px solid rgba(255,255,255,0.12);background:rgba(255,255,255,0.06);color:#fff;} button{padding:0.7rem 1rem;border-radius:10px;border:none;background:#2563eb;color:#fff;font-weight:600;} a{color:#cbd5e1;}</style></head><body>" +
                "<div class=\"container\"><aside class=\"sidebar\"><h2>Navigation</h2><nav><a class=\"user-item\" href=\"/dashboard\">Dashboard</a><a class=\"user-item\" href=\"/users\">Users</a></nav><hr/> <p style=\"opacity:0.8;font-size:0.9rem;\">Logged in as " + escapeHtml(currentUsername) + "</p></aside><main class=\"main\">" + welcomeHtml + feedback + "<div style=\"display:flex;gap:2rem;align-items:flex-start;\"><div style=\"width:300px;\">" + left.toString() + "</div><div style=\"flex:1;\">" + right + "</div></div></main></div></body></html>";
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

    private static String buildDashboardPage(String firstName, String username, boolean isAdmin) {
        String adminLink = isAdmin ? "<a class=\"button\" href=\"/add-user\">Add User</a>" : "";

        return "<!DOCTYPE html>" +
                "<html lang=\"en\">" +
                "<head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>Dashboard</title>" +
                "<style>body{margin:0;font-family:Arial,Helvetica,sans-serif;background:linear-gradient(135deg,#0f172a,#2563eb);color:#f8fafc;} .container{display:flex;min-height:100vh;} .sidebar{width:260px;padding:1.5rem;background:rgba(255,255,255,0.04);border-right:1px solid rgba(255,255,255,0.04);} .nav-item{display:block;padding:0.6rem;border-radius:10px;color:#e6eef8;text-decoration:none;margin-bottom:0.35rem;} .nav-item:hover{background:rgba(255,255,255,0.03);} .main{flex:1;padding:2rem;} .card{max-width:720px;padding:2rem;border-radius:20px;background:rgba(255,255,255,0.06);box-shadow:0 20px 45px rgba(15,23,42,0.25);backdrop-filter:blur(6px);} .actions{display:flex;gap:1rem;margin-top:1rem;} a.button{padding:0.6rem 0.9rem;border-radius:10px;background:#2563eb;color:#fff;text-decoration:none;font-weight:600;} a.button:hover{background:#1d4ed8;} </style></head><body>" +
                "<div class=\"container\"><aside class=\"sidebar\"><h2>Navigation</h2><nav><a class=\"nav-item\" href=\"/users\">Users</a></nav><hr/>" +
                "<p style=\"opacity:0.8;font-size:0.9rem;\">Logged in as " + escapeHtml(username) + "</p></aside><main class=\"main\">" +
                "<div class=\"card\"><h1 style=\"margin:0 0 0.5rem;\">Welcome, " + escapeHtml(firstName) + "</h1>" +
                "<p style=\"margin:0 0 1rem;opacity:0.9;\">Your username is " + escapeHtml(username) + ".</p>" +
                "<div class=\"actions\">" + adminLink + "<a class=\"button\" href=\"/profile\">Edit Profile</a><a class=\"button\" href=\"/logout\">Logout</a></div>" +
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
