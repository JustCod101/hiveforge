package com.hiveforge.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 沙盒守卫 — 路径安全验证工具类。
 *
 * 防止 Worker 通过 "../../../etc/passwd" 等路径逃逸出工作目录，
 * 也防止符号链接绕过。
 */
public final class SandboxGuard {

    private static final Logger log = LoggerFactory.getLogger(SandboxGuard.class);

    private SandboxGuard() {}

    /**
     * 将相对路径解析为绝对路径，验证未逃逸出工作目录。
     *
     * @param workingDir   沙盒根（Worker 工作目录）
     * @param relativePath 待验证的相对路径
     * @return 安全的绝对路径
     * @throws SandboxViolationException 如果路径越界
     */
    public static Path resolve(String workingDir, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new SandboxViolationException("Empty path");
        }

        try {
            Path base = Path.of(workingDir).toAbsolutePath().normalize();
            Path resolved = base.resolve(relativePath).toAbsolutePath().normalize();

            if (!resolved.startsWith(base)) {
                log.warn("[Sandbox] Path escape attempt: base={}, resolved={}, input='{}'",
                        base, resolved, relativePath);
                throw new SandboxViolationException(
                        "Path escape denied: '" + relativePath + "' resolves outside working directory");
            }

            return resolved;
        } catch (SandboxViolationException e) {
            throw e;
        } catch (Exception e) {
            throw new SandboxViolationException("Invalid path: '" + relativePath + "' — " + e.getMessage());
        }
    }

    /**
     * 验证路径在指定子目录内（如 output/）。
     * 比 resolve() 更严格 — 限定到工作目录的子目录而不是整个工作目录。
     *
     * @param workingDir  沙盒根
     * @param subDir      允许的子目录名（如 "output"）
     * @param relativePath 待验证的路径
     * @return 安全的绝对路径
     */
    public static Path resolveInSubDir(String workingDir, String subDir, String relativePath) {
        Path base = Path.of(workingDir, subDir).toAbsolutePath().normalize();
        Path resolved;

        try {
            resolved = base.resolve(relativePath).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new SandboxViolationException(
                    "Invalid path: '" + relativePath + "' in subdir '" + subDir + "'");
        }

        if (!resolved.startsWith(base)) {
            log.warn("[Sandbox] SubDir escape attempt: base={}, resolved={}, input='{}'",
                    base, resolved, relativePath);
            throw new SandboxViolationException(
                    "Path must be within '" + subDir + "/' directory, got: '" + relativePath + "'");
        }

        return resolved;
    }

    /**
     * 沙盒违规异常
     */
    public static class SandboxViolationException extends RuntimeException {
        public SandboxViolationException(String message) {
            super(message);
        }
    }
}
