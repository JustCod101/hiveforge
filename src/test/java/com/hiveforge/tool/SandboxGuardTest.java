package com.hiveforge.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SandboxGuard 沙盒守卫测试类。
 * 用于验证路径逃逸（Path Traversal）防御逻辑。
 */
class SandboxGuardTest {

    @TempDir
    Path tempDir;

    /**
     * 测试场景 1：合法的相对路径解析
     * 当给定在沙盒内的相对路径时，能够正确解析为绝对路径。
     */
    @Test
    void testResolveValidPath() {
        String workingDir = tempDir.toString();
        Path resolved = SandboxGuard.resolve(workingDir, "test.txt");

        assertEquals(tempDir.resolve("test.txt").toAbsolutePath().normalize(), resolved);
    }

    /**
     * 测试场景 2：合法的子目录路径解析
     */
    @Test
    void testResolveValidSubDirPath() {
        String workingDir = tempDir.toString();
        Path resolved = SandboxGuard.resolve(workingDir, "src/main/test.txt");

        assertEquals(tempDir.resolve("src/main/test.txt").toAbsolutePath().normalize(), resolved);
    }

    /**
     * 测试场景 3：恶意路径逃逸防御 - 使用 ../ 尝试跳出沙盒根目录
     */
    @Test
    void testResolvePathEscapeDenied() {
        String workingDir = tempDir.toString();
        
        SandboxGuard.SandboxViolationException exception = assertThrows(
                SandboxGuard.SandboxViolationException.class,
                () -> SandboxGuard.resolve(workingDir, "../../etc/passwd")
        );

        assertTrue(exception.getMessage().contains("resolves outside working directory"));
    }

    /**
     * 测试场景 4：伪装的逃逸尝试
     * 先进入子目录再尝试跳出沙盒
     */
    @Test
    void testResolveComplexPathEscapeDenied() {
        String workingDir = tempDir.toString();
        
        assertThrows(
                SandboxGuard.SandboxViolationException.class,
                () -> SandboxGuard.resolve(workingDir, "sub/../../secret.txt")
        );
    }

    /**
     * 测试场景 5：空路径应当抛出异常
     */
    @Test
    void testResolveEmptyPath() {
        String workingDir = tempDir.toString();

        assertThrows(
                SandboxGuard.SandboxViolationException.class,
                () -> SandboxGuard.resolve(workingDir, "")
        );
        
        assertThrows(
                SandboxGuard.SandboxViolationException.class,
                () -> SandboxGuard.resolve(workingDir, "   ")
        );
    }

    /**
     * 测试场景 6：严格限制在指定子目录中的合法路径
     */
    @Test
    void testResolveInSubDirValid() {
        String workingDir = tempDir.toString();
        Path resolved = SandboxGuard.resolveInSubDir(workingDir, "output", "result.md");
        
        assertEquals(tempDir.resolve("output/result.md").toAbsolutePath().normalize(), resolved);
    }

    /**
     * 测试场景 7：尝试从限定子目录中逃逸到沙盒其他部分（即便仍在沙盒内，但越过了指定的子目录界限也是违规的）
     */
    @Test
    void testResolveInSubDirEscapeDenied() {
        String workingDir = tempDir.toString();
        
        // 尝试用 ../ 跳出 output 目录去访问沙盒根目录下的 SOUL.md
        SandboxGuard.SandboxViolationException exception = assertThrows(
                SandboxGuard.SandboxViolationException.class,
                () -> SandboxGuard.resolveInSubDir(workingDir, "output", "../SOUL.md")
        );

        assertTrue(exception.getMessage().contains("within 'output/' directory"));
    }
}