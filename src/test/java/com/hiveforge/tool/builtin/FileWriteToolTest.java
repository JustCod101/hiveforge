package com.hiveforge.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hiveforge.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileWriteTool 测试类。
 * 验证向限定的 output/ 目录下写入文件的行为、沙盒防御机制以及内容截断/限制。
 */
class FileWriteToolTest {

    @TempDir
    Path tempDir;

    private FileWriteTool fileWriteTool;
    private ObjectMapper objectMapper;
    private String workingDir;

    @BeforeEach
    void setUp() {
        fileWriteTool = new FileWriteTool();
        objectMapper = new ObjectMapper();
        workingDir = tempDir.toString();
    }

    /**
     * 测试场景 1：成功写入文件到 output/ 目录
     */
    @Test
    void testExecuteSuccess() throws IOException {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("path", "report.md");
        args.put("content", "# Test Report\\nEverything is fine.");

        ToolResult result = fileWriteTool.execute(args, workingDir);

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Successfully written"));

        // 验证文件是否实际存在于 output/ 目录中
        Path expectedPath = tempDir.resolve("output/report.md");
        assertTrue(Files.exists(expectedPath));
        assertEquals("# Test Report\\nEverything is fine.", Files.readString(expectedPath));
    }

    /**
     * 测试场景 2：带有多级子目录的写入（工具应当自动创建不存在的父目录）
     */
    @Test
    void testExecuteWithSubDirectories() throws IOException {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("path", "data/2024/results.csv");
        args.put("content", "id,value\\n1,100");

        ToolResult result = fileWriteTool.execute(args, workingDir);

        assertTrue(result.isSuccess());

        Path expectedPath = tempDir.resolve("output/data/2024/results.csv");
        assertTrue(Files.exists(expectedPath));
        assertEquals("id,value\\n1,100", Files.readString(expectedPath));
    }

    /**
     * 测试场景 3：沙盒拦截 - 尝试越权写入 output/ 外的目录
     */
    @Test
    void testExecuteSandboxViolation() {
        ObjectNode args = objectMapper.createObjectNode();
        // 尝试通过 ../ 跳出 output/ 目录，将文件写到 Worker 根目录（例如想覆盖 SOUL.md）
        args.put("path", "../HACKED.md");
        args.put("content", "evil content");

        ToolResult result = fileWriteTool.execute(args, workingDir);

        assertFalse(result.isSuccess(), "不允许通过 ../ 逃逸出 output 目录");
        assertTrue(result.getOutput().contains("within 'output/' directory"));
    }

    /**
     * 测试场景 4：缺少必填参数的防御
     */
    @Test
    void testExecuteMissingParameters() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("content", "some data"); // 缺少 path

        ToolResult result = fileWriteTool.execute(args, workingDir);

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("Missing required parameter: path"));
    }

    /**
     * 测试场景 5：写入超大内容的防御
     */
    @Test
    void testExecuteContentTooLarge() {
        StringBuilder largeContent = new StringBuilder();
        // FileWriteTool 限制 1MB
        largeContent.append("a".repeat(1024 * 1024 + 1));

        ObjectNode args = objectMapper.createObjectNode();
        args.put("path", "large.txt");
        args.put("content", largeContent.toString());

        ToolResult result = fileWriteTool.execute(args, workingDir);

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("Content too large"));
    }
}