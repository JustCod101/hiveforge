package com.hiveforge.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * 数据库配置。
 * 现在主要依赖 Spring Boot 的自动配置。
 * 数据库 Schema 初始化已通过 spring.sql.init.mode=always 自动由 schema.sql 处理。
 */
@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    // Schema initialization is now handled automatically by Spring Boot (spring.sql.init.mode=always)
    // using src/main/resources/schema.sql.
    // data/ directory is ensured in HiveForgeApplication.main().
}
