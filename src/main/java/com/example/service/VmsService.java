package com.example.service;

import com.example.config.AppProperties;
import com.example.map.SaudiMapGeometry;
import com.example.security.PasswordService;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
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
        String sessionId = UUID.randomUUID().toString();
        users.update("INSERT INTO sessions(session_id, username, last_activity_ms) VALUES (?, ?, ?)",
                sessionId, username, System.currentTimeMillis());
        logActivity(username, "USER_LOGIN", username, "User logged in");
        Map<String, Object> me = buildMe(username, u);
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
                "statuses", listStatusDefs()
        );
    }

    public void updateVenueItem(String username, int optionId, String itemName, String itemStatus) {
        Map<String, Object> user = loadUser(username);
        Map<String, Object> option = findVenue(optionId);
        if (option == null) throw new ApiException(404, "Venue not found");
        if (!canAccessVenue(user, option)) throw new ApiException(403, "Forbidden");
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

    public byte[] activityLogsPdf(String username, String userFilter, String eventTypeFilter, String fromDate, String toDate) {
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
            doc.add(new Paragraph("Generated: " + Instant.now(), body));
            doc.add(new Paragraph(" "));
            PdfPTable table = new PdfPTable(new float[]{2f, 1.6f, 1.4f, 2f, 2.2f});
            table.setWidthPercentage(100);
            for (String h : List.of("When", "User", "Event", "Target", "Details")) {
                table.addCell(new PdfPCell(new Phrase(h, header)));
            }
            for (Map<String, Object> e : entries) {
                table.addCell(new PdfPCell(new Phrase(str(e.get("changedAt")), body)));
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
            case "USER_LOGIN" -> "User logged in";
            case "USER_LOGOUT" -> "User logged out";
            case "VENUE_CREATED" -> "Venue created";
            case "WORK_ITEM_CREATED" -> "Work item created";
            default -> eventType;
        };
    }

    private static boolean isValidEventType(String eventType) {
        return switch (eventType) {
            case "VENUE_STATUS_CHANGE", "USER_CREATED", "USER_DELETED", "USER_ENABLED", "USER_DISABLED",
                 "USER_LOGIN", "USER_LOGOUT", "VENUE_CREATED", "WORK_ITEM_CREATED" -> true;
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
                String serialized = serializeRoles(roles);
                users.update("UPDATE users SET role=?, is_admin=? WHERE username=?",
                        serialized, hasAdmin(serialized) ? 1 : 0, target);
                return Map.of("message", "Roles updated");
            }
            case "update" -> {
                firstName = trim(firstName); lastName = trim(lastName); email = trim(email);
                if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty()) {
                    throw new ApiException(400, "All fields are required.");
                }
                if (!validRoles(roles)) throw new ApiException(400, "Invalid role selected.");
                String serialized = serializeRoles(roles);
                users.update("UPDATE users SET first_name=?, last_name=?, email=?, role=?, is_admin=? WHERE username=?",
                        firstName, lastName, email, serialized, hasAdmin(serialized) ? 1 : 0, target);
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
                invalidateProgress();
                yield Map.of("message", "Venue updated");
            }
            case "venue-delete", "delete" -> {
                int id = num(body.get("id"));
                if (id <= 0) throw new ApiException(400, "Invalid venue.");
                status.update("DELETE FROM nav_work_items WHERE nav_option_id=?", id);
                users.update("DELETE FROM nav_options WHERE id=?", id);
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
                invalidateDefs();
                yield Map.of("message", "Work item updated");
            }
            case "work-item-delete" -> {
                int id = num(body.get("id"));
                if (id <= 0) throw new ApiException(400, "Invalid work item.");
                String old = status.query("SELECT name FROM work_item_defs WHERE id=?", rs -> rs.next() ? rs.getString(1) : null, id);
                status.update("DELETE FROM work_item_defs WHERE id=?", id);
                if (old != null) status.update("DELETE FROM nav_work_items WHERE item_name=?", old);
                invalidateDefs();
                yield Map.of("message", "Work item deleted");
            }
            case "status-add" -> {
                String label = trim(str(body.get("label")));
                int percent = num(body.get("percent"));
                if (label.isEmpty()) throw new ApiException(400, "Status label is required.");
                status.update("INSERT INTO status_defs(label, percent_value, sort_order) VALUES (?,?,?)",
                        label, percent, nextSort("status_defs"));
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
                invalidateDefs();
                yield Map.of("message", "Status updated");
            }
            case "status-delete" -> {
                int id = num(body.get("id"));
                if (id <= 0) throw new ApiException(400, "Invalid status.");
                if (listStatusDefs().size() <= 1) throw new ApiException(400, "At least one status option is required.");
                status.update("DELETE FROM status_defs WHERE id=?", id);
                invalidateDefs();
                yield Map.of("message", "Status deleted");
            }
            default -> throw new ApiException(400, "Unknown action");
        };
    }

    public Map<String, Object> status(String username, Integer optionId) {
        requireAdmin(username);
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

    public byte[] statusPdf(String username) {
        requireAdmin(username);
        try {
            List<Map<String, Object>> venues = listOptionProgress();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Document doc = new Document();
            PdfWriter.getInstance(doc, baos);
            doc.open();
            Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            doc.add(new Paragraph("VMS Status Report", title));
            doc.add(new Paragraph(" "));
            PdfPTable table = new PdfPTable(2);
            table.addCell(new PdfPCell(new Phrase("Venue")));
            table.addCell(new PdfPCell(new Phrase("Progress")));
            for (Map<String, Object> v : venues) {
                table.addCell(str(v.get("label")));
                table.addCell(str(v.get("percent")) + "%");
            }
            doc.add(table);
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
        me.put("mustChangePassword", ((Number) u.get("must_change_password")).intValue() == 1);
        me.put("homePath", home);
        me.put("nav", nav);
        return me;
    }

    private List<Map<String, Object>> buildNav(String role) {
        List<Map<String, Object>> nav = new ArrayList<>();
        List<Map<String, Object>> venues = listVenues();
        if (hasAdmin(role)) {
            addNav(nav, "Dashboard", "/dashboard");
            addNav(nav, "Users", "/users");
            addNav(nav, "Admin Panel", "/admin");
            addNav(nav, "Status", "/status");
            addNav(nav, "Logs", "/logs");
            addNav(nav, "Mapview", "/mapview");
            for (Map<String, Object> v : venues) addNav(nav, str(v.get("label")), "/venues/" + v.get("id"));
        } else {
            List<Map<String, Object>> matched = matchedVenues(role, venues);
            if (hasUser(role) || matched.isEmpty()) {
                addNav(nav, "Dashboard", "/dashboard");
                addNav(nav, "Users", "/users");
                addNav(nav, "Logs", "/logs");
                addNav(nav, "Mapview", "/mapview");
            } else {
                addNav(nav, "Mapview", "/mapview");
            }
            for (Map<String, Object> v : matched) addNav(nav, str(v.get("label")), "/venues/" + v.get("id"));
        }
        return nav;
    }

    private String resolveHome(String role, List<Map<String, Object>> nav) {
        if (hasAdmin(role) || hasUser(role)) return "/dashboard";
        for (Map<String, Object> n : nav) {
            String href = str(n.get("href"));
            if (href.startsWith("/venues/")) return href;
        }
        return "/dashboard";
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
                "SELECT first_name, last_name, email, is_admin, must_change_password, role, is_enabled FROM users WHERE username=?",
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
        if (hasAdmin(role)) return true;
        String label = str(option.get("label"));
        return parseRoles(role).stream().anyMatch(r -> r.equalsIgnoreCase(label));
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
