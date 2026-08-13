package com.example.service;

import com.example.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SessionMaintenanceJob {
    private static final Logger log = LoggerFactory.getLogger(SessionMaintenanceJob.class);

    private final SessionStore sessions;
    private final AppProperties props;

    public SessionMaintenanceJob(SessionStore sessions, AppProperties props) {
        this.sessions = sessions;
        this.props = props;
    }

    @Scheduled(fixedRateString = "${app.session-cleanup-interval-ms:300000}")
    public void purgeExpiredSessions() {
        if (!props.serves("core", "all")) {
            return;
        }
        long cutoff = System.currentTimeMillis() - props.getSessionTimeoutMs();
        int removed = sessions.purgeExpired(cutoff);
        if (removed > 0) {
            log.info("Purged {} expired session(s)", removed);
        }
    }
}
