package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = {
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class
})
public class VmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(VmsApplication.class, args);
    }
}
