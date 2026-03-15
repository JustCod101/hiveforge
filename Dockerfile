# ============================================================
# HiveForge — Multi-stage Docker Build
# Stage 1: Maven build
# Stage 2: Lightweight JRE runtime
# ============================================================

# ----- Build Stage -----
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 先拷贝 pom.xml 利用 Docker 缓存层加速依赖下载
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 拷贝源码并构建
COPY src ./src
COPY templates ./templates
RUN mvn package -DskipTests -B

# ----- Runtime Stage -----
FROM eclipse-temurin:17-jre-jammy

LABEL maintainer="HiveForge Team"
LABEL description="HiveForge — Dynamic Agent Swarm Engine"

# 安装常用工具（Worker ShellExecuteTool 可能需要）
RUN apt-get update && apt-get install -y \
    bash curl jq python3 git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 从 builder 阶段拷贝 fat jar
COPY --from=builder /build/target/*.jar app.jar

# 拷贝 DNA 模板
COPY templates ./templates

# 创建数据目录和蜂巢工作目录
RUN mkdir -p /app/data /tmp/hive

# 非 root 用户运行
RUN groupadd -r hiveforge && useradd -r -g hiveforge hiveforge
RUN chown -R hiveforge:hiveforge /app /tmp/hive
USER hiveforge

# 暴露端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# JVM 优化参数
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
