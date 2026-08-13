package com.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String serviceName = "all";
    private long sessionTimeoutMs = 300_000L;
    private String defaultAdminUsername = "admin";
    private String defaultAdminPassword = "Certified01$";
    private String defaultNewUserPassword = "Match123$";
    /** Comma-separated origins, e.g. http://192.168.0.13:8080. Empty = same-origin only. */
    private String corsAllowedOrigins = "";
    /** Set true when serving over HTTPS, or leave false to auto-detect via X-Forwarded-Proto. */
    private boolean cookieSecure = false;
    private String cookieSameSite = "Lax";
    private int loginMaxAttempts = 10;
    private long loginWindowMs = 300_000L;
    private int dbPoolMaxSize = 3;
    private long sessionCacheTtlMs = 30_000L;
    private long sessionCleanupIntervalMs = 300_000L;
    private int maxSessionsPerUser = 5;
    /** mysql (default in Docker) or sqlite */
    private String dbType = "mysql";
    private String dbHost = "mysql";
    private int dbPort = 3306;
    private String dbName = "vms";
    private String dbUsername = "vms";
    private String dbPassword = "vms";

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public long getSessionTimeoutMs() { return sessionTimeoutMs; }
    public void setSessionTimeoutMs(long sessionTimeoutMs) { this.sessionTimeoutMs = sessionTimeoutMs; }
    public String getDefaultAdminUsername() { return defaultAdminUsername; }
    public void setDefaultAdminUsername(String defaultAdminUsername) { this.defaultAdminUsername = defaultAdminUsername; }
    public String getDefaultAdminPassword() { return defaultAdminPassword; }
    public void setDefaultAdminPassword(String defaultAdminPassword) { this.defaultAdminPassword = defaultAdminPassword; }
    public String getDefaultNewUserPassword() { return defaultNewUserPassword; }
    public void setDefaultNewUserPassword(String defaultNewUserPassword) { this.defaultNewUserPassword = defaultNewUserPassword; }
    public String getCorsAllowedOrigins() { return corsAllowedOrigins; }
    public void setCorsAllowedOrigins(String corsAllowedOrigins) { this.corsAllowedOrigins = corsAllowedOrigins; }
    public boolean isCookieSecure() { return cookieSecure; }
    public void setCookieSecure(boolean cookieSecure) { this.cookieSecure = cookieSecure; }
    public String getCookieSameSite() { return cookieSameSite; }
    public void setCookieSameSite(String cookieSameSite) { this.cookieSameSite = cookieSameSite; }
    public int getLoginMaxAttempts() { return loginMaxAttempts; }
    public void setLoginMaxAttempts(int loginMaxAttempts) { this.loginMaxAttempts = loginMaxAttempts; }
    public long getLoginWindowMs() { return loginWindowMs; }
    public void setLoginWindowMs(long loginWindowMs) { this.loginWindowMs = loginWindowMs; }
    public int getDbPoolMaxSize() { return dbPoolMaxSize; }
    public void setDbPoolMaxSize(int dbPoolMaxSize) { this.dbPoolMaxSize = dbPoolMaxSize; }
    public long getSessionCacheTtlMs() { return sessionCacheTtlMs; }
    public void setSessionCacheTtlMs(long sessionCacheTtlMs) { this.sessionCacheTtlMs = sessionCacheTtlMs; }
    public long getSessionCleanupIntervalMs() { return sessionCleanupIntervalMs; }
    public void setSessionCleanupIntervalMs(long sessionCleanupIntervalMs) { this.sessionCleanupIntervalMs = sessionCleanupIntervalMs; }
    public int getMaxSessionsPerUser() { return maxSessionsPerUser; }
    public void setMaxSessionsPerUser(int maxSessionsPerUser) { this.maxSessionsPerUser = maxSessionsPerUser; }
    public String getDbType() { return dbType; }
    public void setDbType(String dbType) { this.dbType = dbType; }
    public String getDbHost() { return dbHost; }
    public void setDbHost(String dbHost) { this.dbHost = dbHost; }
    public int getDbPort() { return dbPort; }
    public void setDbPort(int dbPort) { this.dbPort = dbPort; }
    public String getDbName() { return dbName; }
    public void setDbName(String dbName) { this.dbName = dbName; }
    public String getDbUsername() { return dbUsername; }
    public void setDbUsername(String dbUsername) { this.dbUsername = dbUsername; }
    public String getDbPassword() { return dbPassword; }
    public void setDbPassword(String dbPassword) { this.dbPassword = dbPassword; }

    public boolean isMysql() {
        return dbType == null || dbType.isBlank() || "mysql".equalsIgnoreCase(dbType.trim());
    }

    public String mysqlJdbcUrl() {
        return "jdbc:mysql://" + dbHost + ":" + dbPort + "/" + dbName
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8"
                + "&cachePrepStmts=true&useServerPrepStmts=true&rewriteBatchedStatements=true";
    }

    public Set<String> corsOrigins() {
        if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Set<String> corsOriginHosts() {
        return corsOrigins().stream()
                .map(o -> {
                    try {
                        String v = o.contains("://") ? o : "http://" + o;
                        String host = java.net.URI.create(v).getHost();
                        return host == null ? "" : host.toLowerCase(Locale.ROOT);
                    } catch (Exception ex) {
                        return "";
                    }
                })
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean serves(String... names) {
        String service = serviceName == null ? "all" : serviceName.trim().toLowerCase();
        if ("all".equals(service)) {
            return true;
        }
        for (String name : names) {
            if (service.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
