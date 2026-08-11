package com.example.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig implements WebMvcConfigurer {

    @Bean(name = "usersDataSource")
    DataSource usersDataSource() throws Exception {
        Files.createDirectories(Path.of("data"));
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:data/users.db");
        return ds;
    }

    @Bean(name = "statusDataSource")
    DataSource statusDataSource() throws Exception {
        Files.createDirectories(Path.of("data"));
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:data/status.db");
        return ds;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowCredentials(true);
    }
}
