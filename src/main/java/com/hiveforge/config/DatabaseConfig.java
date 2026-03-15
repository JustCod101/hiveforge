package com.hiveforge.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class DatabaseConfig {

    @Bean
    public CommandLineRunner initDatabase(JdbcTemplate jdbcTemplate) {
        return args -> {
            // Ensure data directory exists
            Files.createDirectories(Path.of("data"));

            // Enable WAL mode for better concurrency
            jdbcTemplate.execute("PRAGMA journal_mode=WAL");
            jdbcTemplate.execute("PRAGMA foreign_keys=ON");

            // Execute schema
            ClassPathResource resource = new ClassPathResource("schema.sql");
            String schema = resource.getContentAsString(StandardCharsets.UTF_8);
            for (String statement : schema.split(";")) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                    jdbcTemplate.execute(trimmed);
                }
            }
        };
    }
}
