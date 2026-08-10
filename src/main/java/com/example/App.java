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
    private static final String DB_URL = "jdbc:sqlite:users.db";
    private static final String SESSION_COOKIE_NAME = "SESSIONID";
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
        server.createContext("/logout", App::handleLogout);
        server.createContext("/", App::handleHome);
        server.setExecutor(null);

        System.out.println("Java application started on http://localhost:" + port);
        server.start();
    }

    private static void initDatabase() throws SQLException {
        try (Connection connection = DriverManager.getConnection(DB_URL);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS users (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "first_name TEXT NOT NULL, " +
                    "last_name TEXT NOT NULL, " +
                    "username TEXT NOT NULL UNIQUE, " +
                    "email TEXT NOT NULL UNIQUE, " +
                    "password TEXT NOT NULL, " +
                    "created_at TEXT NOT NULL)"
            );
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
            String firstName = findFirstNameByCredentials(username, password);
            if (firstName == null) {
                sendHtmlResponse(exchange, 401, buildErrorPage("Invalid username or password. Please try again."));
            } else {
                String sessionId = createSession(username);
                exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE_NAME + "=" + sessionId + "; Path=/; HttpOnly");
                redirect(exchange, "/dashboard");
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
            String firstName = findFirstNameByUsername(username);
            if (firstName == null) {
                redirect(exchange, "/logout");
                return;
            }
            sendHtmlResponse(exchange, 200, buildDashboardPage(firstName, username));
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
        String sql = "INSERT INTO users (first_name, last_name, username, email, password, created_at) VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, firstName);
            statement.setString(2, lastName);
            statement.setString(3, username);
            statement.setString(4, email);
            statement.setString(5, password);
            statement.setString(6, Instant.now().toString());
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

    private static String buildDashboardPage(String firstName, String username) {
        return "<!DOCTYPE html>" +
                "<html lang=\"en\">" +
                "<head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>Dashboard</title>" +
                "<style>body{margin:0;min-height:100vh;display:grid;place-items:center;font-family:Arial,Helvetica,sans-serif;background:linear-gradient(135deg,#0f172a,#2563eb);color:#f8fafc;}" +
                ".card{padding:3rem 2rem;border-radius:24px;background:rgba(255,255,255,0.14);box-shadow:0 20px 45px rgba(15,23,42,0.25);backdrop-filter:blur(10px);text-align:center;max-width:560px;margin:auto;}" +
                ".actions{display:flex;justify-content:center;gap:1rem;margin-top:1.5rem;}a.button{padding:0.95rem 1.25rem;border-radius:14px;background:#2563eb;color:#fff;text-decoration:none;font-weight:600;transition:background 0.2s ease;}a.button:hover{background:#1d4ed8;}" +
                "</style></head><body><main class=\"card\"><h1>Welcome, " + escapeHtml(firstName) + "</h1>" +
                "<p>Your username is " + escapeHtml(username) + ".</p>" +
                "<div class=\"actions\"><a class=\"button\" href=\"/\">Home</a><a class=\"button\" href=\"/logout\">Logout</a></div>" +
                "</main></body></html>";
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
