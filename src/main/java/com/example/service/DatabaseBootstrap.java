package com.example.service;

import com.example.App;
import com.example.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class DatabaseBootstrap {
    private final AppProperties props;

    public DatabaseBootstrap(AppProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() throws Exception {
        // Ensure SERVICE_NAME env is visible to legacy App helpers.
        if (System.getenv("SERVICE_NAME") == null && props.getServiceName() != null) {
            // App reads env directly; docker already sets SERVICE_NAME.
        }
        App.initDatabase();
    }
}
