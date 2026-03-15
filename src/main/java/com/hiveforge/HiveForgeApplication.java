package com.hiveforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class HiveForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(HiveForgeApplication.class, args);
    }
}
