package com.example.service;

import com.example.config.AppProperties;
import com.example.map.SaudiMapGeometry;
import com.example.security.PasswordService;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class VmsService {
    private static final Logger log = LoggerFactory.getLogger(VmsService.class);

    private final JdbcTemplate users;
    private final JdbcTemplate status;
    private final AppProperties props;
    private final PasswordService passwords;

    private final Object progressLock = new Object();
    private volatile List<Map<String, Object>> progressCache;
    private volatile long progressCacheAtMs;
    private final Object defsLock = new Object();
    private volatile List<Map<String, Object>> workItemDefsCache;
    private volatile long workItemDefsCacheAtMs;
    private volatile List<Map<String, Object>> statusDefsCache;
    private volatile long statusDefsCacheAtMs;

    public VmsService(@Qualifier("usersJdbc") JdbcTemplate users,
                      @Qualifier("statusJdbc") JdbcTemplate status,
                      AppProperties props,
                      PasswordService passwords) {
        this.users = users;
        this.status = status;
        this.props = props;
        this.passwords = passwords;
    }

    public static class ApiException extends RuntimeException {
        public final int status;
        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public String resolveSessionUsername(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        List<Map<String, Object>> rows = users.queryForList(
                "SELECT username, last_activity_ms FROM sessions WHERE session_id = ?", sessionId);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        long last = ((Number) row.get("last_activity_ms")).longValue();
        long now = System.currentTimeMillis();
        if (now - last > props.getSessionTimeoutMs()) {
            users.update("DELETE FROM sessions WHERE session_id = ?", sessionId);
            return null;
        }
        if (now - last > 30_000L) {
            users.update("UPDATE sessions SET last_activity_ms = ? WHERE session_id = ?", now, sessionId);
        }
        return String.valueOf(row.get("username"));
    }

    public Map<String, Object> login(String username, String password) {
        username = username == null ? "" : username.trim();
        password = password == null ? "" : password.trim();
        if (username.isEmpty() || password.isEmpty()) {
            throw new ApiException(400, "Please provide both username and password.");
        }
        List<Map<String, Object>> rows = users.queryForList(
                "SELECT first_name, last_name, email, is_admin, must_change_password, role, is_enabled, password FROM users WHERE username = ?",
                username);
        if (rows.isEmpty()) {
            throw new ApiException(401, "Invalid username or password.");
        }
        Map<String, Object> u = rows.get(0);
        String stored = str(u.get("password"));
        if (!passwords.matches(password, stored)) {
            throw new ApiException(401, "Invalid username or password.");
        }
        if (((Number) u.get("is_enabled")).intValue() != 1) {
            throw new ApiException(403, "This account has been disabled. Please contact an administrator.");
        }
        if (!passwords.isHashed(stored)) {
            users.update("UPDATE users SET password=? WHERE username=?", passwords.hash(password), username);
        }
        users.update("UPDATE users SET previous_login_at=last_login_at, last_login_at=? WHERE username=?",
                Instant.now().toString(), username);
        String sessionId = UUID.randomUUID().toString();
        users.update("INSERT INTO sessions(session_id, username, last_activity_ms) VALUES (?, ?, ?)",
                sessionId, username, System.currentTimeMillis());
        logActivity(username, "USER_LOGIN", username, "User logged in");
        Map<String, Object> me = buildMe(username, findUserRow(username));
        me.put("_sessionId", sessionId);
        return me;
    }

    public void logout(String sessionId) {
        if (sessionId != null) {
            String username = resolveSessionUsername(sessionId);
            if (username != null) {
                logActivity(username, "USER_LOGOUT", username, "User logged out");
            }
            users.update("DELETE FROM sessions WHERE session_id = ?", sessionId);
        }
    }

    public Map<String, Object> me(String username) {
        Map<String, Object> u = loadUser(username);
        return buildMe(username, u);
    }

    public Map<String, Object> profile(String username) {
        Map<String, Object> u = loadUser(username);
        return Map.of(
                "username", username,
                "firstName", str(u.get("first_name")),
                "lastName", str(u.get("last_name")),
                "email", str(u.get("email"))
        );
    }

    public void updateProfile(String username, String firstName, String lastName, String email, String password) {
        firstName = trim(firstName); lastName = trim(lastName); email = trim(email); password = trim(password);
        if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty()) {
            throw new ApiException(400, "First name, last name, and email are required.");
        }
        try {
            if (password.isEmpty()) {
                users.update("UPDATE users SET first_name=?, last_name=?, email=? WHERE username=?",
                        firstName, lastName, email, username);
            } else {
                users.update("UPDATE users SET first_name=?, last_name=?, email=?, password=? WHERE username=?",
                        firstName, lastName, email, passwords.hash(password), username);
            }
        } catch (DuplicateKeyException ex) {
            throw new ApiException(400, "The email address is already in use.");
        } catch (Exception ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("UNIQUE")) {
                throw new ApiException(400, "The email address is already in use.");
            }
            throw new ApiException(500, "Unable to save profile changes.");
        }
    }

    public Map<String, Object> changePassword(String username, String password, String confirm) {
        password = trim(password); confirm = trim(confirm);
        if (password.isEmpty() || confirm.isEmpty()) {
            throw new ApiException(400, "Both password fields are required.");
        }
        if (!password.equals(confirm)) {
            throw new ApiException(400, "Passwords do not match. Please re-enter them.");
        }
        users.update("UPDATE users SET password=?, must_change_password=0 WHERE username=?",
                passwords.hash(password), username);
        Map<String, Object> me = me(username);
        return Map.of("message", "Password updated", "homePath", me.get("homePath"));
    }

    public Map<String, Object> getVenue(String username, int optionId) {
        Map<String, Object> user = loadUser(username);
        Map<String, Object> option = findVenue(optionId);
        if (option == null) throw new ApiException(404, "Venue not found");
        if (!canAccessVenue(user, option)) throw new ApiException(403, "Forbidden");
        ensureWorkItems(optionId, str(option.get("label")));
        List<Map<String, Object>> defs = listWorkItemDefs();
        Map<String, String> byName = new LinkedHashMap<>();
        status.query("SELECT item_name, status FROM nav_work_items WHERE nav_option_id=?", rs -> {
            while (rs.next()) byName.put(rs.getString(1), rs.getString(2));
            return null;
        }, optionId);
        List<Map<String, Object>> workItems = new ArrayList<>();
        for (Map<String, Object> def : defs) {
            String name = str(def.get("name"));
            if (byName.containsKey(name)) {
                workItems.add(Map.of("name", name, "status", byName.get(name)));
            }
        }
        return Map.of(
                "id", optionId,
                "label", str(option.get("label")),
                "workItems", workItems,
                "statuses", listStatusDefs(),
                "canEdit", canEditVenue(user, option)
        );
    }

    public void updateVenueItem(String username, int optionId, String itemName, String itemStatus) {
        Map<String, Object> user = loadUser(username);
        Map<String, Object> option = findVenue(optionId);
        if (option == null) throw new ApiException(404, "Venue not found");
        if (!canAccessVenue(user, option)) throw new ApiException(403, "Forbidden");
        if (!canEditVenue(user, option)) throw new ApiException(403, "You have read-only access to this venue.");
        final String name = trim(itemName);
        final String newStatus = trim(itemStatus);
        boolean validItem = listWorkItemDefs().stream().anyMatch(d -> str(d.get("name")).equals(name));
        boolean validStatus = listStatusDefs().stream().anyMatch(d -> str(d.get("label")).equals(newStatus));
        if (name.isEmpty() || !validItem || !validStatus) {
            throw new ApiException(400, "Invalid work item status update.");
        }
        String oldStatus = status.query(
                "SELECT status FROM nav_work_items WHERE nav_option_id=? AND item_name=?",
                rs -> rs.next() ? rs.getString(1) : "Not started",
                optionId, name);
        if (oldStatus == null) oldStatus = "Not started";
        String now = Instant.now().toString();
        long nowMs = System.currentTimeMillis();
        status.update("UPDATE nav_work_items SET status=?, updated_at=? WHERE nav_option_id=? AND item_name=?",
                newStatus, now, optionId, name);
        if (!oldStatus.equals(newStatus)) {
            logActivity(username, "VENUE_STATUS_CHANGE", str(option.get("label")) + " / " + name,
                    oldStatus + " → " + newStatus, str(option.get("label")), name, oldStatus, newStatus);
        }
        invalidateProgress();
    }

    public Map<String, Object> listActivityLogs(String username, String userFilter, String eventTypeFilter, String fromDate, String toDate) {
        requireReportAccess(username);
        StringBuilder sql = new StringBuilder(
                "SELECT id, changed_at, event_type, username, user_display_name, target, details, venue_label, item_name, old_value, new_value " +
                        "FROM activity_logs WHERE 1=1");
        List<Object> args = new ArrayList<>();
        String uf = trim(userFilter);
        if (!uf.isEmpty()) {
            sql.append(" AND (LOWER(username) LIKE ? OR LOWER(user_display_name) LIKE ?)");
            String like = "%" + uf.toLowerCase(Locale.ROOT) + "%";
            args.add(like);
            args.add(like);
        }
        String et = trim(eventTypeFilter);
        if (!et.isEmpty()) {
            if (!isValidEventType(et)) throw new ApiException(400, "Invalid event type filter.");
            sql.append(" AND event_type = ?");
            args.add(et);
        }
        Long fromMs = parseDateStartMs(fromDate);
        if (fromMs != null) {
            sql.append(" AND changed_at_ms >= ?");
            args.add(fromMs);
        }
        Long toMs = parseDateEndMs(toDate);
        if (toMs != null) {
            sql.append(" AND changed_at_ms <= ?");
            args.add(toMs);
        }
        sql.append(" ORDER BY changed_at_ms DESC LIMIT 1000");
        List<Map<String, Object>> rows = status.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String eventType = str(row.get("event_type"));
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", ((Number) row.get("id")).longValue());
            e.put("changedAt", str(row.get("changed_at")));
            e.put("username", str(row.get("username")));
            e.put("userDisplayName", str(row.get("user_display_name")));
            e.put("eventType", eventType);
            e.put("eventLabel", eventLabel(eventType));
            e.put("target", str(row.get("target")));
            e.put("details", str(row.get("details")));
            e.put("venueLabel", str(row.get("venue_label")));
            e.put("itemName", str(row.get("item_name")));
            e.put("oldValue", str(row.get("old_value")));
            e.put("newValue", str(row.get("new_value")));
            entries.add(e);
        }
        return Map.of("entries", entries);
    }

    public byte[] activityLogsPdf(String username, String userFilter, String eventTypeFilter, String fromDate, String toDate, String timeZone) {
        requireReportAccess(username);
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) listActivityLogs(username, userFilter, eventTypeFilter, fromDate, toDate).get("entries");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Document doc = new Document();
            PdfWriter.getInstance(doc, baos);
            doc.open();
            Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
            Font header = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 8);
            doc.add(new Paragraph("VMS Activity Logs", title));
            doc.add(new Paragraph("Generated: " + formatPdfLocalNow(timeZone), body));
            doc.add(new Paragraph(" "));
            PdfPTable table = new PdfPTable(new float[]{2f, 1.6f, 1.4f, 2f, 2.2f});
            table.setWidthPercentage(100);
            for (String h : List.of("Date&Time", "User", "Event", "Target", "Details")) {
                table.addCell(new PdfPCell(new Phrase(h, header)));
            }
            for (Map<String, Object> e : entries) {
                table.addCell(new PdfPCell(new Phrase(formatPdfLocalDateTime(str(e.get("changedAt")), timeZone), body)));
                table.addCell(new PdfPCell(new Phrase(str(e.get("userDisplayName")) + " (" + str(e.get("username")) + ")", body)));
                table.addCell(new PdfPCell(new Phrase(str(e.get("eventLabel")), body)));
                table.addCell(new PdfPCell(new Phrase(str(e.get("target")), body)));
                table.addCell(new PdfPCell(new Phrase(str(e.get("details")), body)));
            }
            if (entries.isEmpty()) {
                PdfPCell empty = new PdfPCell(new Phrase("No log entries found.", body));
                empty.setColspan(5);
                table.addCell(empty);
            }
            doc.add(table);
            doc.close();
            return baos.toByteArray();
        } catch (Exception ex) {
            throw new ApiException(500, "Unable to export logs PDF right now.");
        }
    }

    private void recordUserRoleChange(String actorUsername, String targetUsername,
                                      String oldRolesSerialized, String newRolesSerialized, String changeType) {
        if (Objects.equals(oldRolesSerialized, newRolesSerialized)) return;
        String oldLabel = formatRolesForDisplay(oldRolesSerialized);
        String newLabel = formatRolesForDisplay(newRolesSerialized);
        String now = Instant.now().toString();
        long nowMs = System.currentTimeMillis();
        String actorDisplay = displayNameFor(actorUsername);
        try {
            users.update(
                    "INSERT INTO user_role_history(changed_at,changed_at_ms,target_username,actor_username,actor_display_name,old_roles,new_roles,change_type) VALUES (?,?,?,?,?,?,?,?)",
                    now, nowMs, targetUsername, actorUsername, actorDisplay, oldRolesSerialized, newRolesSerialized, changeType);
        } catch (Exception ex) {
            log.warn("Failed to write role history for {}: {}", targetUsername, ex.getMessage());
        }
        logActivity(actorUsername, "USER_ROLES_UPDATED", targetUsername,
                "Roles changed for " + targetUsername + ": " + oldLabel + " → " + newLabel,
                "", "", oldLabel, newLabel);
    }

    private static String formatRolesForDisplay(String serialized) {
        if (serialized == null || serialized.isBlank()) return "(none)";
        return String.join(", ", parseRoles(serialized));
    }

    private void logActivity(String actorUsername, String eventType, String target, String details) {
        logActivity(actorUsername, eventType, target, details, "", "", "", "");
    }

    private void logActivity(String actorUsername, String eventType, String target, String details,
                             String venueLabel, String itemName, String oldValue, String newValue) {
        try {
            String display = displayNameFor(actorUsername);
            String now = Instant.now().toString();
            long nowMs = System.currentTimeMillis();
            status.update(
                    "INSERT INTO activity_logs(changed_at,changed_at_ms,event_type,username,user_display_name,target,details,venue_label,item_name,old_value,new_value) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    now, nowMs, eventType, actorUsername, display, target, details, venueLabel, itemName, oldValue, newValue);
        } catch (Exception ex) {
            log.warn("Failed to write activity log [{}] for {}: {}", eventType, actorUsername, ex.getMessage());
        }
    }

    private String displayNameFor(String username) {
        try {
            Map<String, Object> u = findUserRow(username);
            String display = (str(u.get("first_name")) + " " + str(u.get("last_name"))).trim();
            return display.isEmpty() ? username : display;
        } catch (Exception ex) {
            return username;
        }
    }

    private static String eventLabel(String eventType) {
        return switch (eventType) {
            case "VENUE_STATUS_CHANGE" -> "Venue status changed";
            case "USER_CREATED" -> "User created";
            case "USER_DELETED" -> "User deleted";
            case "USER_ENABLED" -> "User enabled";
            case "USER_DISABLED" -> "User disabled";
            case "USER_ROLES_UPDATED" -> "User roles updated";
            case "USER_ROLES_BULK_UPDATED" -> "User roles bulk updated";
            case "USER_LOGIN" -> "User logged in";
            case "USER_LOGOUT" -> "User logged out";
            case "VENUE_CREATED" -> "Venue created";
            case "VENUE_UPDATED" -> "Venue updated";
            case "VENUE_DELETED" -> "Venue deleted";
            case "WORK_ITEM_CREATED" -> "Work item created";
            case "WORK_ITEM_UPDATED" -> "Work item updated";
            case "WORK_ITEM_DELETED" -> "Work item deleted";
            case "STATUS_CREATED" -> "Status created";
            case "STATUS_UPDATED" -> "Status updated";
            case "STATUS_DELETED" -> "Status deleted";
            default -> eventType;
        };
    }

    private static boolean isValidEventType(String eventType) {
        return switch (eventType) {
            case "VENUE_STATUS_CHANGE", "USER_CREATED", "USER_DELETED", "USER_ENABLED", "USER_DISABLED",
                 "USER_ROLES_UPDATED", "USER_ROLES_BULK_UPDATED",
                 "USER_LOGIN", "USER_LOGOUT", "VENUE_CREATED", "VENUE_UPDATED", "VENUE_DELETED",
                 "WORK_ITEM_CREATED", "WORK_ITEM_UPDATED", "WORK_ITEM_DELETED",
                 "STATUS_CREATED", "STATUS_UPDATED", "STATUS_DELETED" -> true;
            default -> false;
        };
    }

    private static Long parseDateStartMs(String date) {
        if (date == null || date.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(date.trim())
                    .atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli();
        } catch (Exception ex) {
            return null;
        }
    }

    private static Long parseDateEndMs(String date) {
        if (date == null || date.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(date.trim())
                    .plusDays(1)
                    .atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli() - 1;
        } catch (Exception ex) {
            return null;
        }
    }

    private static ZoneId resolveZone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(timeZone.trim());
        } catch (Exception ex) {
            return ZoneId.systemDefault();
        }
    }

    private static String formatPdfLocalDateTime(String iso, String timeZone) {
        if (iso == null || iso.isBlank()) return "";
        try {
            Instant instant = Instant.parse(iso);
            return DateTimeFormatter.ofPattern("M/d/yyyy, h:mm:ss a", Locale.US)
                    .withZone(resolveZone(timeZone))
                    .format(instant);
        } catch (Exception ex) {
            return iso;
        }
    }

    private static String formatPdfLocalNow(String timeZone) {
        return formatPdfLocalDateTime(Instant.now().toString(), timeZone);
    }

    private void requireReportAccess(String username) {
        Map<String, Object> user = loadUser(username);
        String role = roleOf(user);
        if (!hasAdmin(role) && !hasUser(role)) {
            throw new ApiException(403, "Forbidden");
        }
    }

    public Map<String, Object> listUsers(String currentUsername) {
        Map<String, Object> current = loadUser(currentUsername);
        String role = roleOf(current);
        if (!hasAdmin(role) && !hasUser(role)) throw new ApiException(403, "Forbidden");
        boolean canManage = hasAdmin(role);
        List<Map<String, Object>> userRows = users.queryForList(
                "SELECT username, first_name, last_name, email, is_admin, role, is_enabled FROM users ORDER BY created_at DESC");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> u : userRows) {
            String r = roleOf(u);
            List<String> roles = parseRoles(r);
            out.add(Map.of(
                    "username", str(u.get("username")),
                    "firstName", str(u.get("first_name")),
                    "lastName", str(u.get("last_name")),
                    "email", str(u.get("email")),
                    "role", r,
                    "roles", roles,
                    "enabled", ((Number) u.get("is_enabled")).intValue() == 1,
                    "isAdmin", hasAdmin(r)
            ));
        }
        return Map.of("users", out, "venues", listVenues(), "canManage", canManage);
    }

    public Map<String, Object> createUser(String currentUsername, String firstName, String lastName, String email) {
        requireAdmin(currentUsername);
        firstName = trim(firstName); lastName = trim(lastName); email = trim(email);
        if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty()) {
            throw new ApiException(400, "First name, last name, and email are required.");
        }
        String newUsername = email.toLowerCase(Locale.ROOT);
        try {
            users.update(
                    "INSERT INTO users(first_name,last_name,username,email,password,is_admin,must_change_password,created_at,role,is_enabled) VALUES (?,?,?,?,?,0,1,?,?,1)",
                    firstName, lastName, newUsername, email, passwords.hash(props.getDefaultNewUserPassword()),
                    Instant.now().toString(), "user");
        } catch (Exception ex) {
            throw new ApiException(400, "A user with that email already exists.");
        }
        logActivity(currentUsername, "USER_CREATED", newUsername,
                "Created user " + firstName + " " + lastName + " (" + email + ")");
        return Map.of("message", "User created. Email is the username and default password is "
                + props.getDefaultNewUserPassword() + ".");
    }

    public byte[] exportUsersCsv(String currentUsername) {
        requireAdmin(currentUsername);
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("firstName,lastName,email,status,roles\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) listUsers(currentUsername).get("users");
        for (Map<String, Object> u : rows) {
            sb.append(csvCell(str(u.get("firstName")))).append(',');
            sb.append(csvCell(str(u.get("lastName")))).append(',');
            sb.append(csvCell(str(u.get("email")))).append(',');
            sb.append(csvCell(Boolean.TRUE.equals(u.get("enabled")) ? "Active" : "Disabled")).append(',');
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) u.get("roles");
            sb.append(csvCell(String.join("|", roles))).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public Map<String, Object> importUsersCsv(String currentUsername, byte[] csvBytes) {
        requireAdmin(currentUsername);
        if (csvBytes == null || csvBytes.length == 0) {
            throw new ApiException(400, "CSV file is empty.");
        }
        String text = new String(csvBytes, StandardCharsets.UTF_8).replace("\uFEFF", "");
        List<String[]> rows = parseCsvRows(text);
        if (rows.isEmpty()) throw new ApiException(400, "CSV file has no data rows.");

        int start = 0;
        if (rows.get(0).length >= 3 && "firstname".equals(rows.get(0)[0].trim().toLowerCase(Locale.ROOT))) {
            start = 1;
        }
        if (start >= rows.size()) throw new ApiException(400, "CSV file has no user rows.");

        int created = 0;
        int updated = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        for (int i = start; i < rows.size(); i++) {
            String[] cols = rows.get(i);
            if (cols.length == 0 || Arrays.stream(cols).allMatch(c -> c == null || c.isBlank())) continue;
            int rowNum = i + 1;
            try {
                if (cols.length < 5) {
                    throw new ApiException(400, "Expected 5 columns: firstName,lastName,email,status,roles");
                }
                String firstName = trim(cols[0]);
                String lastName = trim(cols[1]);
                String email = trim(cols[2]);
                String status = trim(cols[3]);
                String rolesRaw = trim(cols[4]);
                if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty()) {
                    throw new ApiException(400, "firstName, lastName, and email are required.");
                }
                List<String> roles = parseRolesImport(rolesRaw);
                if (!validRoles(roles)) throw new ApiException(400, "Invalid roles: " + rolesRaw);
                boolean enabled = parseEnabledStatus(status);
                String username = email.toLowerCase(Locale.ROOT);
                String serialized = serializeRoles(roles);

                List<Map<String, Object>> existing = users.queryForList(
                        "SELECT username FROM users WHERE username=? OR LOWER(email)=?",
                        username, username);
                if (existing.isEmpty()) {
                    users.update(
                            "INSERT INTO users(first_name,last_name,username,email,password,is_admin,must_change_password,created_at,role,is_enabled) VALUES (?,?,?,?,?,?,1,?,?,?)",
                            firstName, lastName, username, email, passwords.hash(props.getDefaultNewUserPassword()),
                            hasAdmin(serialized) ? 1 : 0, Instant.now().toString(), serialized, enabled ? 1 : 0);
                    logActivity(currentUsername, "USER_CREATED", username,
                            "Imported user " + firstName + " " + lastName + " (" + email + ")");
                    created++;
                } else {
                    String target = str(existing.get(0).get("username"));
                    if (!enabled && (target.equals(currentUsername) || props.getDefaultAdminUsername().equals(target))) {
                        throw new ApiException(400, "Cannot disable protected account.");
                    }
                    Map<String, Object> row = findUserRow(target);
                    String oldRole = roleOf(row);
                    users.update(
                            "UPDATE users SET first_name=?, last_name=?, email=?, role=?, is_admin=?, is_enabled=? WHERE username=?",
                            firstName, lastName, email, serialized, hasAdmin(serialized) ? 1 : 0, enabled ? 1 : 0, target);
                    if (!enabled) users.update("DELETE FROM sessions WHERE username=?", target);
                    recordUserRoleChange(currentUsername, target, oldRole, serialized, "import");
                    updated++;
                }
            } catch (ApiException ex) {
                skipped++;
                errors.add("Row " + rowNum + ": " + ex.getMessage());
            } catch (Exception ex) {
                skipped++;
                errors.add("Row " + rowNum + ": " + (ex.getMessage() == null ? "Import failed." : ex.getMessage()));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "Import finished. Created " + created + ", updated " + updated + ", skipped " + skipped + ".");
        result.put("created", created);
        result.put("updated", updated);
        result.put("skipped", skipped);
        result.put("errors", errors);
        return result;
    }

    private static String csvCell(String value) {
        if (value == null) value = "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static List<String> parseRolesImport(String raw) {
        if (raw == null || raw.isBlank()) return normalizeRoles(List.of("user"));
        if (raw.contains("|")) {
            List<String> parts = new ArrayList<>();
            for (String part : raw.split("\\|")) {
                String t = part.trim();
                if (!t.isEmpty()) parts.add(t);
            }
            return normalizeRoles(parts);
        }
        return normalizeRoles(parseRoles(raw));
    }

    private static boolean parseEnabledStatus(String status) {
        String s = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        return !(s.equals("disabled") || s.equals("inactive") || s.equals("no") || s.equals("false") || s.equals("0"));
    }

    private static List<String[]> parseCsvRows(String text) {
        List<String[]> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                cells.add(cell.toString());
                cell.setLength(0);
                rows.add(cells.toArray(String[]::new));
                cells = new ArrayList<>();
            } else if (c != '\r') {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        if (!cells.isEmpty() && !(cells.size() == 1 && cells.get(0).isBlank())) {
            rows.add(cells.toArray(String[]::new));
        }
        return rows;
    }

    public Map<String, Object> bulkUpdateUserRoles(String currentUsername, List<String> usernames, List<String> roles) {
        requireAdmin(currentUsername);
        roles = normalizeRoles(roles);
        if (!validRoles(roles)) throw new ApiException(400, "Invalid role selected.");
        if (usernames == null || usernames.isEmpty()) {
            throw new ApiException(400, "Select at least one user.");
        }
        String serialized = serializeRoles(roles);
        String roleLabel = roles.isEmpty() ? "(none)" : String.join(", ", roles);
        int updated = 0;
        List<String> errors = new ArrayList<>();
        for (String target : usernames) {
            target = trim(target);
            if (target.isEmpty()) continue;
            try {
                Map<String, Object> row = findUserRow(target);
                String oldRole = roleOf(row);
                users.update("UPDATE users SET role=?, is_admin=? WHERE username=?",
                        serialized, hasAdmin(serialized) ? 1 : 0, target);
                recordUserRoleChange(currentUsername, target, oldRole, serialized, "bulk");
                updated++;
            } catch (ApiException ex) {
                errors.add(target + ": " + ex.getMessage());
            } catch (Exception ex) {
                errors.add(target + ": unable to update");
            }
        }
        if (updated > 0) {
            logActivity(currentUsername, "USER_ROLES_BULK_UPDATED", String.valueOf(updated),
                    "Bulk-updated roles for " + updated + " user(s) to " + roleLabel);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "Updated roles for " + updated + " user(s).");
        result.put("updated", updated);
        result.put("errors", errors);
        return result;
    }

    public Map<String, Object> mutateUser(String currentUsername, String action, String target,
                                          String firstName, String lastName, String email, List<String> roles) {
        requireAdmin(currentUsername);
        action = trim(action).toLowerCase(Locale.ROOT);
        target = trim(target);
        if (target.isEmpty()) throw new ApiException(400, "Username required");
        roles = normalizeRoles(roles);
        switch (action) {
            case "delete" -> {
                if (target.equals(currentUsername)) throw new ApiException(400, "You cannot delete your own account.");
                if (props.getDefaultAdminUsername().equals(target)) throw new ApiException(400, "The default admin account cannot be deleted.");
                logActivity(currentUsername, "USER_DELETED", target, "Deleted user " + target);
                users.update("DELETE FROM users WHERE username=?", target);
                users.update("DELETE FROM sessions WHERE username=?", target);
                return Map.of("message", "User deleted");
            }
            case "toggle-enabled" -> {
                if (target.equals(currentUsername)) throw new ApiException(400, "You cannot disable your own account.");
                if (props.getDefaultAdminUsername().equals(target)) throw new ApiException(400, "The default admin account cannot be disabled.");
                Map<String, Object> t = findUserRow(target);
                boolean enabled = ((Number) t.get("is_enabled")).intValue() == 1;
                users.update("UPDATE users SET is_enabled=? WHERE username=?", enabled ? 0 : 1, target);
                if (enabled) users.update("DELETE FROM sessions WHERE username=?", target);
                logActivity(currentUsername, enabled ? "USER_DISABLED" : "USER_ENABLED", target,
                        enabled ? "Disabled user " + target : "Enabled user " + target);
                return Map.of("message", "User status updated");
            }
            case "update-role" -> {
                if (!validRoles(roles)) throw new ApiException(400, "Invalid role selected.");
                Map<String, Object> t = findUserRow(target);
                String oldRole = roleOf(t);
                String serialized = serializeRoles(roles);
                users.update("UPDATE users SET role=?, is_admin=? WHERE username=?",
                        serialized, hasAdmin(serialized) ? 1 : 0, target);
                recordUserRoleChange(currentUsername, target, oldRole, serialized, "update");
                return Map.of("message", "Roles updated");
            }
            case "update" -> {
                firstName = trim(firstName); lastName = trim(lastName); email = trim(email);
                if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty()) {
                    throw new ApiException(400, "All fields are required.");
                }
                if (!validRoles(roles)) throw new ApiException(400, "Invalid role selected.");
                Map<String, Object> t = findUserRow(target);
                String oldRole = roleOf(t);
                String serialized = serializeRoles(roles);
                users.update("UPDATE users SET first_name=?, last_name=?, email=?, role=?, is_admin=? WHERE username=?",
                        firstName, lastName, email, serialized, hasAdmin(serialized) ? 1 : 0, target);
                recordUserRoleChange(currentUsername, target, oldRole, serialized, "update");
                return Map.of("message", "User updated");
            }
            default -> throw new ApiException(400, "Unknown action");
        }
    }

    public Map<String, Object> adminSnapshot(String username) {
        requireAdmin(username);
        return Map.of(
                "venues", listVenues(),
                "workItems", listWorkItemDefs(),
                "statuses", listStatusDefs()
        );
    }

    public Map<String, Object> adminAction(String username, Map<String, Object> body) {
        requireAdmin(username);
        String action = trim(str(body.get("action"))).toLowerCase(Locale.ROOT);
        return switch (action) {
            case "venue-add", "add" -> {
                String label = trim(str(body.get("label")));
                if (label.isEmpty()) throw new ApiException(400, "Venue name is required.");
                users.update("INSERT INTO nav_options(label, created_at) VALUES (?,?)", label, Instant.now().toString());
                Integer id = users.queryForObject("SELECT id FROM nav_options WHERE label=?", Integer.class, label);
                if (id != null) ensureWorkItems(id, label);
                logActivity(username, "VENUE_CREATED", label, "Created venue " + label);
                invalidateProgress();
                yield Map.of("message", "Venue added");
            }
            case "venue-update", "update" -> {
                int id = num(body.get("id"));
                String label = trim(str(body.get("label")));
                if (id <= 0 || label.isEmpty()) throw new ApiException(400, "Invalid venue update.");
                String old = users.queryForObject("SELECT label FROM nav_options WHERE id=?", String.class, id);
                users.update("UPDATE nav_options SET label=? WHERE id=?", label, id);
                status.update("UPDATE nav_work_items SET option_label=? WHERE nav_option_id=?", label, id);
                if (old != null && !old.equals(label)) renameRoleLabel(old, label);
                logActivity(username, "VENUE_UPDATED", label,
                        old != null && !old.equals(label) ? "Renamed venue " + old + " → " + label : "Updated venue " + label);
                invalidateProgress();
                yield Map.of("message", "Venue updated");
            }
            case "venue-delete", "delete" -> {
                int id = num(body.get("id"));
                if (id <= 0) throw new ApiException(400, "Invalid venue.");
                String label = users.query("SELECT label FROM nav_options WHERE id=?", rs -> rs.next() ? rs.getString(1) : null, id);
                status.update("DELETE FROM nav_work_items WHERE nav_option_id=?", id);
                users.update("DELETE FROM nav_options WHERE id=?", id);
                logActivity(username, "VENUE_DELETED", label != null ? label : String.valueOf(id),
                        "Deleted venue " + (label != null ? label : id));
                invalidateProgress();
                yield Map.of("message", "Venue deleted");
            }
            case "work-item-add" -> {
                String name = trim(str(body.get("name")));
                if (name.isEmpty()) throw new ApiException(400, "Work item name is required.");
                int sort = nextSort("work_item_defs");
                status.update("INSERT INTO work_item_defs(name, sort_order) VALUES (?,?)", name, sort);
                seedWorkItem(name);
                logActivity(username, "WORK_ITEM_CREATED", name, "Created work item " + name, "", name, "", "");
                invalidateDefs();
                yield Map.of("message", "Work item added");
            }
            case "work-item-update" -> {
                int id = num(body.get("id"));
                String name = trim(str(body.get("name")));
                if (id <= 0 || name.isEmpty()) throw new ApiException(400, "Invalid work item update.");
                String old = status.queryForObject("SELECT name FROM work_item_defs WHERE id=?", String.class, id);
                status.update("UPDATE work_item_defs SET name=? WHERE id=?", name, id);
                if (old != null && !old.equals(name)) {
                    status.update("UPDATE nav_work_items SET item_name=?, updated_at=? WHERE item_name=?",
                            name, Instant.now().toString(), old);
                }
                logActivity(username, "WORK_ITEM_UPDATED", name,
                        old != null && !old.equals(name) ? "Renamed work item " + old + " → " + name : "Updated work item " + name,
                        "", name, old != null ? old : "", name);
                invalidateDefs();
                yield Map.of("message", "Work item updated");
            }
            case "work-item-delete" -> {
                int id = num(body.get("id"));
                if (id <= 0) throw new ApiException(400, "Invalid work item.");
                String old = status.query("SELECT name FROM work_item_defs WHERE id=?", rs -> rs.next() ? rs.getString(1) : null, id);
                status.update("DELETE FROM work_item_defs WHERE id=?", id);
                if (old != null) status.update("DELETE FROM nav_work_items WHERE item_name=?", old);
                logActivity(username, "WORK_ITEM_DELETED", old != null ? old : String.valueOf(id),
                        "Deleted work item " + (old != null ? old : id));
                invalidateDefs();
                yield Map.of("message", "Work item deleted");
            }
            case "status-add" -> {
                String label = trim(str(body.get("label")));
                int percent = num(body.get("percent"));
                if (label.isEmpty()) throw new ApiException(400, "Status label is required.");
                status.update("INSERT INTO status_defs(label, percent_value, sort_order) VALUES (?,?,?)",
                        label, percent, nextSort("status_defs"));
                logActivity(username, "STATUS_CREATED", label, "Created status " + label + " (" + percent + "%)");
                invalidateDefs();
                yield Map.of("message", "Status added");
            }
            case "status-update" -> {
                int id = num(body.get("id"));
                String label = trim(str(body.get("label")));
                int percent = num(body.get("percent"));
                if (id <= 0 || label.isEmpty()) throw new ApiException(400, "Invalid status update.");
                String old = status.queryForObject("SELECT label FROM status_defs WHERE id=?", String.class, id);
                status.update("UPDATE status_defs SET label=?, percent_value=? WHERE id=?", label, percent, id);
                if (old != null && !old.equals(label)) {
                    status.update("UPDATE nav_work_items SET status=?, updated_at=? WHERE status=?",
                            label, Instant.now().toString(), old);
                }
                logActivity(username, "STATUS_UPDATED", label,
                        old != null && !old.equals(label)
                                ? "Renamed status " + old + " → " + label + " (" + percent + "%)"
                                : "Updated status " + label + " (" + percent + "%)");
                invalidateDefs();
                yield Map.of("message", "Status updated");
            }
            case "status-delete" -> {
                int id = num(body.get("id"));
                if (id <= 0) throw new ApiException(400, "Invalid status.");
                if (listStatusDefs().size() <= 1) throw new ApiException(400, "At least one status option is required.");
                String label = status.query("SELECT label FROM status_defs WHERE id=?", rs -> rs.next() ? rs.getString(1) : null, id);
                status.update("DELETE FROM status_defs WHERE id=?", id);
                logActivity(username, "STATUS_DELETED", label != null ? label : String.valueOf(id),
                        "Deleted status " + (label != null ? label : id));
                invalidateDefs();
                yield Map.of("message", "Status deleted");
            }
            default -> throw new ApiException(400, "Unknown action");
        };
    }

    public Map<String, Object> status(String username, Integer optionId) {
        requireStandardAccess(username);
        List<Map<String, Object>> venues = listOptionProgress();
        Map<String, Object> selected = null;
        if (optionId != null) {
            selected = venues.stream().filter(v -> Objects.equals(v.get("id"), optionId)).findFirst().orElse(null);
        }
        if (selected == null && !venues.isEmpty()) selected = venues.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("venues", venues);
        out.put("selected", selected);
        return out;
    }

    public byte[] statusPdf(String username, String timeZone) {
        requireStandardAccess(username);
        try {
            List<Map<String, Object>> venues = listOptionProgress();
            Map<String, Integer> statusPercent = new LinkedHashMap<>();
            for (Map<String, Object> s : listStatusDefs()) {
                statusPercent.put(str(s.get("label")), ((Number) s.get("percent")).intValue());
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Document doc = new Document();
            PdfWriter.getInstance(doc, baos);
            doc.open();
            Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            Font section = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
            Font header = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 8);
            doc.add(new Paragraph("VMS Status Report", title));
            doc.add(new Paragraph("Generated: " + formatPdfLocalNow(timeZone), body));
            doc.add(new Paragraph(" "));

            doc.add(new Paragraph("Overall Progress", section));
            doc.add(new Paragraph(" "));
            PdfPTable summary = new PdfPTable(new float[]{3f, 1f});
            summary.setWidthPercentage(100);
            summary.addCell(new PdfPCell(new Phrase("Venue", header)));
            summary.addCell(new PdfPCell(new Phrase("Progress", header)));
            for (Map<String, Object> v : venues) {
                summary.addCell(new PdfPCell(new Phrase(str(v.get("label")), body)));
                int pct = ((Number) v.get("percent")).intValue();
                summary.addCell(progressPdfCell(pct, body, true));
            }
            doc.add(summary);
            doc.add(new Paragraph(" "));

            doc.add(new Paragraph("Detail by Venue", section));
            doc.add(new Paragraph(" "));
            for (Map<String, Object> v : venues) {
                String venueLabel = str(v.get("label"));
                doc.add(new Paragraph(venueLabel + " — " + str(v.get("percent")) + "% overall", header));
                PdfPTable detail = new PdfPTable(new float[]{2.2f, 2f, 1f});
                detail.setWidthPercentage(100);
                detail.setSpacingBefore(4f);
                detail.setSpacingAfter(10f);
                for (String h : List.of("Work Item", "Status", "Progress")) {
                    detail.addCell(new PdfPCell(new Phrase(h, header)));
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> workItems = (List<Map<String, Object>>) v.get("workItems");
                if (workItems == null || workItems.isEmpty()) {
                    PdfPCell empty = new PdfPCell(new Phrase("No work items.", body));
                    empty.setColspan(3);
                    detail.addCell(empty);
                } else {
                    for (Map<String, Object> item : workItems) {
                        String statusLabel = str(item.get("status"));
                        int pct = statusPercent.getOrDefault(statusLabel, 0);
                        detail.addCell(new PdfPCell(new Phrase(str(item.get("name")), body)));
                        detail.addCell(new PdfPCell(new Phrase(statusLabel, body)));
                        detail.addCell(progressPdfCell(pct, body, false));
                    }
                }
                doc.add(detail);
            }
            doc.close();
            return baos.toByteArray();
        } catch (Exception ex) {
            throw new ApiException(500, "Unable to export status PDF right now.");
        }
    }

    public Map<String, Object> mapview(String username) {
        loadUser(username);
        List<Map<String, Object>> venues = listOptionProgress();
        Map<Integer, Integer> markersPerCity = new HashMap<>();
        List<double[]> placedPoints = new ArrayList<>();
        List<Map<String, Object>> markers = new ArrayList<>();

        for (Map<String, Object> v : venues) {
            int cityIndex = SaudiMapGeometry.resolveCityIndex(str(v.get("label")));
            if (cityIndex < 0) {
                continue;
            }
            int slot = markersPerCity.merge(cityIndex, 1, Integer::sum) - 1;
            double[] xy = SaudiMapGeometry.projectCity(cityIndex);
            xy = SaudiMapGeometry.offsetMapPoint(xy[0], xy[1], slot);

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

            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("id", v.get("id"));
            marker.put("label", v.get("label"));
            marker.put("x", Math.round(xy[0] * 10.0) / 10.0);
            marker.put("y", Math.round(xy[1] * 10.0) / 10.0);
            marker.put("percent", v.get("percent"));
            marker.put("color", v.get("color"));
            markers.add(marker);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("viewBox", SaudiMapGeometry.viewBox());
        out.put("landPath", SaudiMapGeometry.saudiOutlinePath());
        out.put("markers", markers);
        out.put("venues", venues);
        return out;
    }

    // --- helpers ---

    private Map<String, Object> buildMe(String username, Map<String, Object> u) {
        String role = roleOf(u);
        List<String> roles = parseRoles(role);
        List<Map<String, Object>> nav = buildNav(role);
        String home = resolveHome(role, nav);
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("username", username);
        me.put("firstName", str(u.get("first_name")));
        me.put("lastName", str(u.get("last_name")));
        me.put("email", str(u.get("email")));
        me.put("role", role);
        me.put("roles", roles);
        me.put("isAdmin", hasAdmin(role));
        me.put("readOnly", hasUser(role) && !hasAdmin(role));
        me.put("mustChangePassword", ((Number) u.get("must_change_password")).intValue() == 1);
        me.put("homePath", home);
        me.put("nav", nav);
        String lastLogin = str(u.get("previous_login_at"));
        if (!lastLogin.isEmpty()) {
            me.put("lastLoginAt", lastLogin);
        }
        return me;
    }

    private List<Map<String, Object>> buildNav(String role) {
        List<Map<String, Object>> nav = new ArrayList<>();
        List<Map<String, Object>> venues = listVenues();
        if (hasAdmin(role)) {
            addNav(nav, "Home", "/home");
            addNav(nav, "Users", "/users");
            addNav(nav, "Admin Panel", "/admin");
            addNav(nav, "Status", "/status");
            addNav(nav, "Logs", "/logs");
            addNav(nav, "Mapview", "/mapview");
            for (Map<String, Object> v : venues) addNav(nav, str(v.get("label")), "/venues/" + v.get("id"));
        } else if (hasUser(role)) {
            addNav(nav, "Home", "/home");
            addNav(nav, "Users", "/users");
            addNav(nav, "Status", "/status");
            addNav(nav, "Logs", "/logs");
            addNav(nav, "Mapview", "/mapview");
            for (Map<String, Object> v : venues) addNav(nav, str(v.get("label")), "/venues/" + v.get("id"));
        } else {
            List<Map<String, Object>> matched = matchedVenues(role, venues);
            addNav(nav, "Home", "/home");
            addNav(nav, "Mapview", "/mapview");
            for (Map<String, Object> v : matched) addNav(nav, str(v.get("label")), "/venues/" + v.get("id"));
        }
        return nav;
    }

    private String resolveHome(String role, List<Map<String, Object>> nav) {
        return "/home";
    }

    private void addNav(List<Map<String, Object>> nav, String label, String href) {
        nav.add(Map.of("id", 0, "label", label, "href", href));
    }

    private Map<String, Object> loadUser(String username) {
        Map<String, Object> u = findUserRow(username);
        if (((Number) u.get("is_enabled")).intValue() != 1) throw new ApiException(401, "Unauthorized");
        return u;
    }

    /** Loads a user row even if disabled (for admin enable/disable/edit of targets). */
    private Map<String, Object> findUserRow(String username) {
        List<Map<String, Object>> rows = users.queryForList(
                "SELECT first_name, last_name, email, is_admin, must_change_password, role, is_enabled, previous_login_at FROM users WHERE username=?",
                username);
        if (rows.isEmpty()) throw new ApiException(404, "User not found");
        return rows.get(0);
    }

    private void requireAdmin(String username) {
        if (!hasAdmin(roleOf(loadUser(username)))) throw new ApiException(403, "Forbidden");
    }

    private Map<String, Object> findVenue(int id) {
        List<Map<String, Object>> rows = users.queryForList("SELECT id, label FROM nav_options WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> listVenues() {
        return users.queryForList("SELECT id, label FROM nav_options ORDER BY label ASC");
    }

    private boolean canAccessVenue(Map<String, Object> user, Map<String, Object> option) {
        String role = roleOf(user);
        if (hasAdmin(role) || hasUser(role)) return true;
        String label = str(option.get("label"));
        return parseRoles(role).stream().anyMatch(r -> r.equalsIgnoreCase(label));
    }

    private boolean canEditVenue(Map<String, Object> user, Map<String, Object> option) {
        String role = roleOf(user);
        if (hasAdmin(role)) return true;
        String label = str(option.get("label"));
        return parseRoles(role).stream()
                .anyMatch(r -> !r.equalsIgnoreCase("admin") && !r.equalsIgnoreCase("user") && r.equalsIgnoreCase(label));
    }

    private void requireStandardAccess(String username) {
        Map<String, Object> user = loadUser(username);
        String role = roleOf(user);
        if (!hasAdmin(role) && !hasUser(role)) {
            throw new ApiException(403, "Forbidden");
        }
    }

    private List<Map<String, Object>> matchedVenues(String roleCsv, List<Map<String, Object>> venues) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String role : parseRoles(roleCsv)) {
            if (role.equalsIgnoreCase("admin") || role.equalsIgnoreCase("user")) continue;
            for (Map<String, Object> v : venues) {
                if (str(v.get("label")).equalsIgnoreCase(role) && out.stream().noneMatch(x -> Objects.equals(x.get("id"), v.get("id")))) {
                    out.add(v);
                }
            }
        }
        out.sort(Comparator.comparing(v -> str(v.get("label")), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private void ensureWorkItems(int optionId, String label) {
        String defaultStatus = listStatusDefs().stream().findFirst().map(s -> str(s.get("label"))).orElse("Not started");
        String sql = insertIgnoreNavWorkItemSql();
        for (Map<String, Object> def : listWorkItemDefs()) {
            status.update(sql, optionId, label, str(def.get("name")), defaultStatus, Instant.now().toString());
        }
    }

    private void seedWorkItem(String name) {
        String defaultStatus = listStatusDefs().stream().findFirst().map(s -> str(s.get("label"))).orElse("Not started");
        String sql = insertIgnoreNavWorkItemSql();
        for (Map<String, Object> v : listVenues()) {
            status.update(sql, v.get("id"), v.get("label"), name, defaultStatus, Instant.now().toString());
        }
        invalidateProgress();
    }

    private String insertIgnoreNavWorkItemSql() {
        String prefix = props.isMysql() ? "INSERT IGNORE INTO" : "INSERT OR IGNORE INTO";
        return prefix + " nav_work_items(nav_option_id, option_label, item_name, status, updated_at) VALUES (?,?,?,?,?)";
    }

    private List<Map<String, Object>> listWorkItemDefs() {
        long now = System.currentTimeMillis();
        if (workItemDefsCache != null && now - workItemDefsCacheAtMs < 1000) return workItemDefsCache;
        synchronized (defsLock) {
            now = System.currentTimeMillis();
            if (workItemDefsCache != null && now - workItemDefsCacheAtMs < 1000) return workItemDefsCache;
            List<Map<String, Object>> rows = status.queryForList(
                    "SELECT id, name, sort_order AS sortOrder FROM work_item_defs ORDER BY sort_order ASC, id ASC");
            workItemDefsCache = List.copyOf(rows);
            workItemDefsCacheAtMs = now;
            return workItemDefsCache;
        }
    }

    private List<Map<String, Object>> listStatusDefs() {
        long now = System.currentTimeMillis();
        if (statusDefsCache != null && now - statusDefsCacheAtMs < 1000) return statusDefsCache;
        synchronized (defsLock) {
            now = System.currentTimeMillis();
            if (statusDefsCache != null && now - statusDefsCacheAtMs < 1000) return statusDefsCache;
            List<Map<String, Object>> rows = status.queryForList(
                    "SELECT id, label, percent_value AS percent, sort_order AS sortOrder FROM status_defs ORDER BY sort_order ASC, id ASC");
            statusDefsCache = List.copyOf(rows);
            statusDefsCacheAtMs = now;
            return statusDefsCache;
        }
    }

    private List<Map<String, Object>> listOptionProgress() {
        long now = System.currentTimeMillis();
        if (progressCache != null && now - progressCacheAtMs < 3000) return progressCache;
        synchronized (progressLock) {
            now = System.currentTimeMillis();
            if (progressCache != null && now - progressCacheAtMs < 3000) return progressCache;
            Map<String, Integer> pct = new HashMap<>();
            for (Map<String, Object> s : listStatusDefs()) {
                pct.put(str(s.get("label")), ((Number) s.get("percent")).intValue());
            }
            List<Map<String, Object>> defs = listWorkItemDefs();
            Map<Integer, Map<String, String>> items = new HashMap<>();
            status.query("SELECT nav_option_id, item_name, status FROM nav_work_items", rs -> {
                while (rs.next()) {
                    items.computeIfAbsent(rs.getInt(1), k -> new LinkedHashMap<>())
                            .put(rs.getString(2), rs.getString(3));
                }
                return null;
            });
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> venue : listVenues()) {
                int id = ((Number) venue.get("id")).intValue();
                Map<String, String> byName = items.getOrDefault(id, Map.of());
                List<Map<String, Object>> workItems = new ArrayList<>();
                int sum = 0; int count = 0;
                for (Map<String, Object> def : defs) {
                    String name = str(def.get("name"));
                    String st = byName.get(name);
                    if (st != null) {
                        workItems.add(Map.of("name", name, "status", st));
                        sum += pct.getOrDefault(st, 0);
                        count++;
                    }
                }
                int overall = count == 0 ? 0 : Math.round(sum / (float) count);
                result.add(Map.of(
                        "id", id,
                        "label", str(venue.get("label")),
                        "percent", overall,
                        "color", progressColor(overall),
                        "workItems", workItems
                ));
            }
            progressCache = List.copyOf(result);
            progressCacheAtMs = System.currentTimeMillis();
            return progressCache;
        }
    }

    private void renameRoleLabel(String oldLabel, String newLabel) {
        List<Map<String, Object>> all = users.queryForList("SELECT username, role FROM users");
        for (Map<String, Object> row : all) {
            List<String> roles = parseRoles(str(row.get("role")));
            boolean changed = false;
            for (int i = 0; i < roles.size(); i++) {
                if (roles.get(i).equalsIgnoreCase(oldLabel)) {
                    roles.set(i, newLabel);
                    changed = true;
                }
            }
            if (changed) {
                String serialized = serializeRoles(roles);
                users.update("UPDATE users SET role=?, is_admin=? WHERE username=?",
                        serialized, hasAdmin(serialized) ? 1 : 0, row.get("username"));
            }
        }
    }

    private int nextSort(String table) {
        Integer v = status.queryForObject("SELECT COALESCE(MAX(sort_order),0)+1 FROM " + table, Integer.class);
        return v == null ? 1 : v;
    }

    private void invalidateProgress() { progressCache = null; progressCacheAtMs = 0; }
    private void invalidateDefs() {
        workItemDefsCache = null; workItemDefsCacheAtMs = 0;
        statusDefsCache = null; statusDefsCacheAtMs = 0;
        invalidateProgress();
    }

    private boolean validRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) return false;
        Set<String> venues = listVenues().stream().map(v -> str(v.get("label")).toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        for (String r : normalizeRoles(roles)) {
            String key = r.toLowerCase(Locale.ROOT);
            if (!key.equals("admin") && !key.equals("user") && !venues.contains(key)) return false;
        }
        return true;
    }

    private static String progressColor(int percent) {
        int p = Math.max(0, Math.min(100, percent));
        if (p >= 100) return "#16a34a";
        if (p >= 75) return "#65a30d";
        if (p >= 50) return "#ca8a04";
        if (p >= 25) return "#ea580c";
        return "#dc2626";
    }

    private static Color hexToColor(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        return new Color(
                Integer.parseInt(h.substring(0, 2), 16),
                Integer.parseInt(h.substring(2, 4), 16),
                Integer.parseInt(h.substring(4, 6), 16)
        );
    }

    private static PdfPCell progressPdfCell(int percent, Font font, boolean overallLabel) throws DocumentException {
        int p = Math.max(0, Math.min(100, percent));
        Color fill = hexToColor(progressColor(p));
        Color track = new Color(226, 232, 240);

        PdfPTable container = new PdfPTable(1);
        container.setWidthPercentage(100);

        PdfPTable bar = new PdfPTable(2);
        bar.setWidthPercentage(100);
        float left = Math.max(p, 0.5f);
        float right = Math.max(100 - p, 0.5f);
        bar.setWidths(new float[]{left, right});

        PdfPCell filled = new PdfPCell();
        filled.setBackgroundColor(p > 0 ? fill : track);
        filled.setFixedHeight(10);
        filled.setBorder(PdfPCell.NO_BORDER);

        PdfPCell remainder = new PdfPCell();
        remainder.setBackgroundColor(track);
        remainder.setFixedHeight(10);
        remainder.setBorder(PdfPCell.NO_BORDER);

        bar.addCell(filled);
        bar.addCell(remainder);

        PdfPCell barWrap = new PdfPCell(bar);
        barWrap.setBorder(PdfPCell.NO_BORDER);
        barWrap.setPadding(0);
        barWrap.setPaddingBottom(3);
        container.addCell(barWrap);

        Font pctFont = new Font(font);
        pctFont.setColor(fill);
        String label = overallLabel ? p + "% overall" : p + "%";
        PdfPCell text = new PdfPCell(new Phrase(label, pctFont));
        text.setBorder(PdfPCell.NO_BORDER);
        text.setPadding(0);
        container.addCell(text);

        PdfPCell outer = new PdfPCell(container);
        outer.setPadding(5);
        return outer;
    }

    private static List<String> parseRoles(String csv) {
        List<String> roles = new ArrayList<>();
        if (csv == null || csv.isBlank()) return roles;
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) roles.add(t);
        }
        return roles;
    }

    private static List<String> normalizeRoles(List<String> roles) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (roles != null) {
            for (String role : roles) {
                if (role == null) continue;
                String trimmed = role.trim();
                if (trimmed.isEmpty()) continue;
                boolean exists = unique.stream().anyMatch(e -> e.equalsIgnoreCase(trimmed));
                if (!exists) {
                    if ("admin".equalsIgnoreCase(trimmed)) unique.add("admin");
                    else if ("user".equalsIgnoreCase(trimmed)) unique.add("user");
                    else unique.add(trimmed);
                }
            }
        }
        if (unique.isEmpty()) unique.add("user");
        return new ArrayList<>(unique);
    }

    private static String serializeRoles(List<String> roles) {
        return String.join(",", normalizeRoles(roles));
    }

    private static boolean hasAdmin(String role) {
        return parseRoles(role).stream().anyMatch(r -> r.equalsIgnoreCase("admin"));
    }

    private static boolean hasUser(String role) {
        return parseRoles(role).stream().anyMatch(r -> r.equalsIgnoreCase("user"));
    }

    private static String roleOf(Map<String, Object> u) {
        String role = str(u.get("role"));
        if (!role.isEmpty()) return role;
        return ((Number) u.getOrDefault("is_admin", 0)).intValue() == 1 ? "admin" : "user";
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String trim(String s) { return s == null ? "" : s.trim(); }
    private static int num(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o).replaceAll("[^0-9-]", "")); }
        catch (Exception e) { return 0; }
    }
}
