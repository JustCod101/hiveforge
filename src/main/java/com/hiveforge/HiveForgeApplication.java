package com.hiveforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

@SpringBootApplication
@EnableAsync
public class HiveForgeApplication {

    public static void main(String[] args) {
        ensureDataDirectory();
        loadEnvFile();
        SpringApplication.run(HiveForgeApplication.class, args);
    }

    private static void ensureDataDirectory() {
        try {
            Files.createDirectories(Paths.get("data"));
        } catch (IOException e) {
            System.err.println("Fatal: Failed to create data directory: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void loadEnvFile() {
        Path envPath = Paths.get(".env");
        if (!Files.exists(envPath)) {
            return;
        }

        try (Stream<String> lines = Files.lines(envPath)) {
            lines.filter(line -> !line.isBlank() && !line.startsWith("#"))
                 .forEach(line -> {
                     int idx = line.indexOf('=');
                     if (idx > 0) {
                         String key = line.substring(0, idx).trim();
                         String value = line.substring(idx + 1).trim();
                         // 只在系统属性未设置时才设置
                         if (System.getProperty(key) == null && System.getenv(key) == null) {
                             System.setProperty(key, value);
                         }
                     }
                 });
        } catch (IOException e) {
            System.err.println("Warning: Failed to load .env file: " + e.getMessage());
        }
    }
}
