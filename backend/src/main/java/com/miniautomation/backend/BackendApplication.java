package com.miniautomation.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// @EnableAsync now lives on com.miniautomation.backend.config.AsyncConfig,
// alongside the named "ddTaskExecutor" bean it enables.
@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

}
