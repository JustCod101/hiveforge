package com.hiveforge.tool;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ToolRegistry {

    private final JdbcTemplate jdbcTemplate;
    private final Map<String, String> descriptionCache = new ConcurrentHashMap<>();

    public ToolRegistry(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String getDescription(String toolName) {
        return descriptionCache.computeIfAbsent(toolName, name -> {
            try {
                return jdbcTemplate.queryForObject(
                        "SELECT description FROM tool_registry WHERE tool_name = ? AND enabled = 1",
                        String.class, name);
            } catch (Exception e) {
                return toolName + ": no description available";
            }
        });
    }

    public void register(String toolName, String description, String parameterSchema,
                          String permissionLevel) {
        jdbcTemplate.update("""
            INSERT INTO tool_registry (tool_name, description, parameter_schema, permission_level)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(tool_name) DO UPDATE SET
                description = excluded.description,
                parameter_schema = excluded.parameter_schema,
                permission_level = excluded.permission_level
            """, toolName, description, parameterSchema, permissionLevel);
        descriptionCache.remove(toolName);
    }
}
