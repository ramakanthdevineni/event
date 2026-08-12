package com.example.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig implements WebMvcConfigurer {
    private final AppProperties props;

    public AppConfig(AppProperties props) {
        this.props = props;
    }

    @Bean(name = "usersDataSource")
    DataSource usersDataSource() throws Exception {
        return createDataSource("users-pool");
    }

    @Bean(name = "statusDataSource")
    DataSource statusDataSource() throws Exception {
        // Same MySQL database; separate pool so status/work-item traffic does not starve auth.
        return createDataSource("status-pool");
    }

    @Bean
    HealthIndicator dbHealthIndicator(@org.springframework.beans.factory.annotation.Qualifier("usersDataSource") DataSource ds) {
        return new DataSourceHealthIndicator(ds, "SELECT 1");
    }

    private DataSource createDataSource(String poolName) throws Exception {
        if (props.isMysql()) {
            HikariConfig config = new HikariConfig();
            config.setPoolName(poolName);
            config.setJdbcUrl(props.mysqlJdbcUrl());
            config.setUsername(props.getDbUsername());
            config.setPassword(props.getDbPassword());
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            config.setMaximumPoolSize(8);
            config.setMinimumIdle(1);
            config.setConnectionTimeout(15_000);
            config.setInitializationFailTimeout(-1);
            return new HikariDataSource(config);
        }
        Files.createDirectories(Path.of("data"));
        String file = "users-pool".equals(poolName) ? "data/users.db" : "data/status.db";
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + file);
        return ds;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        Set<String> origins = props.corsOrigins();
        if (origins.isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowCredentials(true);
    }
}
