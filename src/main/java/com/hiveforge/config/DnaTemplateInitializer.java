package com.hiveforge.config;

import com.hiveforge.repository.DnaTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 启动时扫描 templates/ 目录，将 DNA 模板文件加载到 SQLite。
 * 每个子目录 = 一个模板，包含 SOUL.template.md 和 AGENTS.template.md。
 */
@Configuration
public class DnaTemplateInitializer {

    private static final Logger log = LoggerFactory.getLogger(DnaTemplateInitializer.class);

    /**
     * 模板定义：name → category + description
     */
    private record TemplateMeta(String category, String description) {}

    @Bean
    @Order(2) // 在 DatabaseConfig 的 initDatabase (Order=默认) 之后执行
    public CommandLineRunner initDnaTemplates(DnaTemplateRepository templateRepo) {
        return args -> {
            Path templatesDir = Path.of("templates");
            if (!Files.isDirectory(templatesDir)) {
                log.warn("[DnaTemplateInit] templates/ directory not found, skipping template loading");
                return;
            }

            try (var dirs = Files.list(templatesDir)) {
                dirs.filter(Files::isDirectory).forEach(dir -> {
                    String templateName = dir.getFileName().toString();
                    Path soulFile = dir.resolve("SOUL.template.md");
                    Path agentsFile = dir.resolve("AGENTS.template.md");

                    if (!Files.exists(soulFile) || !Files.exists(agentsFile)) {
                        log.warn("[DnaTemplateInit] Skipping '{}': missing SOUL.template.md or AGENTS.template.md",
                                templateName);
                        return;
                    }

                    try {
                        String soulTemplate = Files.readString(soulFile);
                        String agentsTemplate = Files.readString(agentsFile);
                        TemplateMeta meta = getTemplateMeta(templateName);

                        templateRepo.save(templateName, meta.category(),
                                soulTemplate, agentsTemplate, meta.description());

                        log.info("[DnaTemplateInit] Loaded template: {} ({})", templateName, meta.category());

                    } catch (IOException e) {
                        log.error("[DnaTemplateInit] Failed to read template files for '{}'",
                                templateName, e);
                    }
                });
            } catch (IOException e) {
                log.error("[DnaTemplateInit] Failed to scan templates directory", e);
            }
        };
    }

    /**
     * 根据模板名称返回分类和描述。
     * 如果将来模板增多，可以改为从模板目录中的 meta.yml 读取。
     */
    private TemplateMeta getTemplateMeta(String templateName) {
        return switch (templateName) {
            case "financial_analyst" -> new TemplateMeta("ANALYSIS",
                    "资深财务分析师，专注财报分析、财务比率计算、风险信号识别，输出结构化分析报告");
            case "researcher" -> new TemplateMeta("RESEARCH",
                    "顶尖研究员与信息分析专家，擅长多源信息搜集、交叉验证、竞品分析，输出系统化调研报告");
            case "code_reviewer" -> new TemplateMeta("CODING",
                    "资深代码审查专家，专注代码质量、安全漏洞、性能问题，输出结构化审查报告");
            default -> new TemplateMeta("GENERAL",
                    templateName + " — 自定义 Agent 模板");
        };
    }
}
