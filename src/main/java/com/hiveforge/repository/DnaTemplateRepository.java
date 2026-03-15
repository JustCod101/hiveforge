package com.hiveforge.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class DnaTemplateRepository {

    private final JdbcTemplate jdbc;

    public record DnaTemplate(int id, String templateName, String category,
                               String soulTemplate, String agentsTemplate,
                               String description, int usageCount, double avgQuality) {}

    public DnaTemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<DnaTemplate> findAll() {
        return jdbc.query("SELECT * FROM dna_template ORDER BY usage_count DESC",
                (rs, rowNum) -> new DnaTemplate(
                        rs.getInt("id"),
                        rs.getString("template_name"),
                        rs.getString("category"),
                        rs.getString("soul_template"),
                        rs.getString("agents_template"),
                        rs.getString("description"),
                        rs.getInt("usage_count"),
                        rs.getDouble("avg_quality")));
    }

    public DnaTemplate findByName(String templateName) {
        var results = jdbc.query(
                "SELECT * FROM dna_template WHERE template_name = ?",
                (rs, rowNum) -> new DnaTemplate(
                        rs.getInt("id"),
                        rs.getString("template_name"),
                        rs.getString("category"),
                        rs.getString("soul_template"),
                        rs.getString("agents_template"),
                        rs.getString("description"),
                        rs.getInt("usage_count"),
                        rs.getDouble("avg_quality")),
                templateName);
        return results.isEmpty() ? null : results.get(0);
    }

    public void save(String templateName, String category, String soulTemplate,
                     String agentsTemplate, String description) {
        jdbc.update("""
            INSERT INTO dna_template (template_name, category, soul_template,
                agents_template, description)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(template_name) DO UPDATE SET
                category = excluded.category,
                soul_template = excluded.soul_template,
                agents_template = excluded.agents_template,
                description = excluded.description
            """, templateName, category, soulTemplate, agentsTemplate, description);
    }

    public void incrementUsageCount(String templateName) {
        jdbc.update(
                "UPDATE dna_template SET usage_count = usage_count + 1 WHERE template_name = ?",
                templateName);
    }
}
