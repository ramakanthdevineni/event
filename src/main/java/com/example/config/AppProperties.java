package com.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String serviceName = "all";
    private long sessionTimeoutMs = 300_000L;
    private String defaultAdminUsername = "admin";
    private String defaultAdminPassword = "Certified01$";
    private String defaultNewUserPassword = "Match123$";

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public long getSessionTimeoutMs() {
        return sessionTimeoutMs;
    }

    public void setSessionTimeoutMs(long sessionTimeoutMs) {
        this.sessionTimeoutMs = sessionTimeoutMs;
    }

    public String getDefaultAdminUsername() {
        return defaultAdminUsername;
    }

    public void setDefaultAdminUsername(String defaultAdminUsername) {
        this.defaultAdminUsername = defaultAdminUsername;
    }

    public String getDefaultAdminPassword() {
        return defaultAdminPassword;
    }

    public void setDefaultAdminPassword(String defaultAdminPassword) {
        this.defaultAdminPassword = defaultAdminPassword;
    }

    public String getDefaultNewUserPassword() {
        return defaultNewUserPassword;
    }

    public void setDefaultNewUserPassword(String defaultNewUserPassword) {
        this.defaultNewUserPassword = defaultNewUserPassword;
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
