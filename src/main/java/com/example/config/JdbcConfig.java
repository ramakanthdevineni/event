package com.example.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class JdbcConfig {

    @Bean(name = "usersJdbc")
    JdbcTemplate usersJdbc(@Qualifier("usersDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean(name = "statusJdbc")
    JdbcTemplate statusJdbc(@Qualifier("statusDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
