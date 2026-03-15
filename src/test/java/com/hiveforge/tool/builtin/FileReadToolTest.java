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
 * FileReadTool 测试类。
 * 验证文件读取、沙盒越界防御、文件大小限制以及异常处理。
 */
class FileReadToolTest {

    @TempDir
    Path tempDir;

    private FileReadTool fileReadTool;
    private ObjectMapper objectMapper;
    private String workingDir;

    @BeforeEach
    void setUp() {
        fileReadTool = new FileReadTool();
        objectMapper = new ObjectMapper();
        workingDir = tempDir.toString();
    }

    /**
     * 测试场景 1：成功读取文件
     */
    @Test
    void testExecuteSuccess() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Hello HiveForge");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("path", "test.txt");

        ToolResult result = fileReadTool.execute(args, workingDir);

        assertTrue(result.isSuccess());
        assertEquals("Hello HiveForge", result.getOutput());
    }

    /**
     * 测试场景 2：尝试读取不存在的文件
     */
    @Test
    void testExecuteFileNotFound() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("path", "non_existent.txt");

        ToolResult result = fileReadTool.execute(args, workingDir);

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("File not found"));
    }

    /**
     * 测试场景 3：尝试读取目录而不是文件
     */
    @Test
    void testExecutePathIsDirectory() throws IOException {
        Files.createDirectories(tempDir.resolve("sub_dir"));

        ObjectNode args = objectMapper.createObjectNode();
        args.put("path", "sub_dir");

        ToolResult result = fileReadTool.execute(args, workingDir);

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("Path is a directory"));
    }

    /**
     * 测试场景 4：沙盒安全拦截 - 尝试读取工作目录外的文件
     */
    @Test
    void testExecuteSandboxViolation() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("path", "../outside.txt");

        ToolResult result = fileReadTool.execute(args, workingDir);

        assertFalse(result.isSuccess());
        // SandboxGuard 会抛出异常，这里验证错误信息被正确捕获并返回
        assertTrue(result.getOutput().contains("outside working directory"));
    }

    /**
     * 测试场景 5：读取大文件拦截（防止 OOM）
     */
    @Test
    void testExecuteFileTooLarge() throws IOException {
        // 创建一个超过 FileReadTool 默认限制 (512KB) 的虚拟文件
        // 为了快速测试，我们使用 Sparse file 特性或者填充大量内容
        Path largeFile = tempDir.resolve("large.bin");
        byte[] chunk = new byte[1024]; // 1KB
        // 写入 513 KB
        try (var out = Files.newOutputStream(largeFile)) {
            for (int i = 0; i < 513; i++) {
                out.write(chunk);
            }
        }

        ObjectNode args = objectMapper.createObjectNode();
        args.put("path", "large.bin");

        ToolResult result = fileReadTool.execute(args, workingDir);

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("File too large"));
    }
}