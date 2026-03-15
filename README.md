# HiveForge — Dynamic Agent Swarm Engine

**Markdown as Agent** 范式的动态智能体蜂群引擎。

HiveForge 将复杂任务自动分解为多个专业 Worker Agent，每个 Worker 拥有独立的 DNA（SOUL.md / AGENTS.md / TASK.md），通过 ReAct 循环自主执行，最终汇总结果后销毁工作目录（用后即焚）。

## 架构概览

```
用户任务 → Manager Agent → [规划] → [孕育 Workers] → [执行] → [聚合] → [销毁]
                                         │
                    ┌────────────────────┼────────────────────┐
                    ▼                    ▼                    ▼
              Worker Alpha         Worker Beta         Worker Gamma
              ├── SOUL.md          ├── SOUL.md          ├── SOUL.md
              ├── AGENTS.md        ├── AGENTS.md        ├── AGENTS.md
              ├── TASK.md          ├── TASK.md          ├── TASK.md
              └── output/          └── output/          └── output/
```

## 核心特性

- **Markdown as Agent** — 目录存在 = Agent 存活，目录删除 = Agent 销毁
- **DNA 模板系统** — 预定义 Agent 人格（金融分析师 / 研究员 / 代码审查员），支持自定义扩展
- **多策略执行** — parallel（并行）/ sequential（串行）/ dag（DAG 拓扑排序）
- **ReAct 循环** — Thought → Action → Observation，最多 10 轮自主推理
- **7 个内置工具** — file_read / file_write / file_list / web_search / http_call / shell_exec / calculate
- **沙盒隔离** — 路径限定 + 命令黑名单 + SSRF 防护
- **SSE 实时推送** — 蜂群全过程事件流式输出
- **DNA 快照持久化** — 即使 Worker 目录销毁，DNA 和执行轨迹可追溯

## 技术栈

| 组件 | 技术 |
|------|------|
| 框架 | Spring Boot 3.2 + JDK 17 |
| 数据库 | SQLite（零运维） |
| LLM | OpenAI 兼容 API（支持任何兼容端点） |
| HTTP | OkHttp 4.12 |
| 模板 | Thymeleaf |
| 搜索 | Tavily / SerpAPI / DuckDuckGo（降级链） |

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- OpenAI 兼容 API（如 DeepSeek、MiniMax 等）

### 本地运行

```bash
# 1. 设置环境变量
export OPENAI_API_KEY=your-api-key
export OPENAI_BASE_URL=https://api.openai.com/v1

# 可选：搜索 API
export TAVILY_API_KEY=your-tavily-key
export SERP_API_KEY=your-serp-key

# 2. 构建并运行
mvn clean package -DskipTests
java -jar target/hiveforge-0.1.0-SNAPSHOT.jar
```

### Docker 运行

```bash
# 1. 创建 .env 文件
cat > .env << EOF
OPENAI_API_KEY=your-api-key
OPENAI_BASE_URL=https://api.openai.com/v1
TAVILY_API_KEY=
SERP_API_KEY=
EOF

# 2. 启动
docker-compose up -d

# 3. 查看日志
docker-compose logs -f hiveforge
```

## API 接口

### 1. 执行蜂群任务（SSE 流式）

```
GET /api/v1/hive/execute?query=分析苹果公司2024年Q4财报
```

SSE 事件类型：

| 事件 | 说明 |
|------|------|
| `hive_start` | 蜂群任务启动 |
| `planning` | Manager 正在规划 |
| `plan_ready` | 任务分解完成 |
| `spawning` | 正在生成 Worker DNA |
| `worker_spawned` | Worker 已创建 |
| `worker_start` | Worker 开始执行 |
| `worker_done` | Worker 执行完成 |
| `aggregating` | 正在汇总结果 |
| `worker_destroyed` | Worker 已销毁 |
| `final_report` | 最终报告 |
| `heartbeat` | 心跳（每 15 秒） |

### 2. 查询任务状态

```
GET /api/v1/hive/{taskId}
```

### 3. 查询 Worker 列表

```
GET /api/v1/hive/{taskId}/workers
```

### 4. 查询 Worker 执行轨迹

```
GET /api/v1/hive/workers/{workerId}/trace
```

### 5. 查询 Worker DNA（即使已销毁）

```
GET /api/v1/hive/workers/{workerId}/dna
```

### 6. 历史任务列表

```
GET /api/v1/hive/history?page=0&size=20
```

## 使用示例

```bash
# 使用 curl 监听 SSE 事件流
curl -N "http://localhost:8080/api/v1/hive/execute?query=比较特斯拉和比亚迪2024年销量数据"

# 查询任务状态
curl http://localhost:8080/api/v1/hive/{taskId}

# 查看 Worker DNA
curl http://localhost:8080/api/v1/hive/workers/{workerId}/dna
```

## DNA 模板

预置 3 个 Agent 人格模板，位于 `templates/` 目录：

| 模板 | 角色 | 工具 |
|------|------|------|
| `financial_analyst` | 金融分析师（15 年投行经验） | web_search, file_read, file_write, calculate, http_call |
| `researcher` | 多源交叉验证研究员 | web_search, http_call, file_read, file_write |
| `code_reviewer` | 代码安全审查专家（OWASP Top 10） | file_read, file_write, shell_exec, web_search |

自定义模板：在 `templates/{name}/` 下创建 `SOUL.template.md` 和 `AGENTS.template.md`，启动时自动加载。

## 项目结构

```
src/main/java/com/hiveforge/
├── HiveForgeApplication.java       # 启动类
├── config/
│   ├── DatabaseConfig.java          # SQLite 初始化 + schema
│   └── DnaTemplateInitializer.java  # DNA 模板加载
├── controller/
│   ├── HiveController.java          # REST API + SSE
│   └── DashboardController.java     # Web 仪表盘
├── manager/
│   ├── ManagerAgent.java            # 蜂群指挥官（规划+调度+销毁）
│   ├── ResultAggregator.java        # LLM 结果聚合
│   ├── TaskPlan.java                # 任务计划
│   ├── WorkerPlan.java              # Worker 计划
│   ├── HiveTask.java                # 任务实体
│   └── HiveResult.java              # 执行结果
├── spawner/
│   ├── WorkerSpawner.java           # DNA 生成器
│   ├── AgentDna.java                # DNA 数据结构
│   └── SpawnedWorker.java           # 已孕育的 Worker
├── worker/
│   ├── WorkerEngine.java            # ReAct 循环执行引擎
│   └── WorkerResult.java            # Worker 执行结果
├── llm/
│   ├── LlmClient.java              # LLM API 客户端
│   ├── ChatMessage.java             # 消息结构
│   ├── ChatResponse.java            # 响应结构
│   ├── ToolCall.java                # 工具调用
│   ├── ToolDefinition.java          # 工具定义
│   └── StreamCallback.java          # SSE 回调接口
├── tool/
│   ├── Tool.java                    # 工具接口
│   ├── ToolRegistry.java            # 工具注册中心
│   ├── ToolExecutor.java            # 工具执行器
│   ├── ToolResult.java              # 执行结果
│   ├── SandboxGuard.java            # 沙盒路径守卫
│   └── builtin/
│       ├── FileReadTool.java        # 文件读取
│       ├── FileWriteTool.java       # 文件写入
│       ├── FileListTool.java        # 目录列表
│       ├── WebSearchTool.java       # 互联网搜索
│       ├── HttpCallTool.java        # HTTP 请求
│       ├── ShellExecuteTool.java    # Shell 命令
│       └── CalculateTool.java       # 数学计算
└── repository/
    ├── HiveTaskRepository.java      # 任务持久化
    ├── WorkerAgentRepository.java   # Worker 持久化
    ├── WorkerTraceRepository.java   # 轨迹持久化
    └── DnaTemplateRepository.java   # 模板持久化
```

## 安全机制

- **沙盒路径隔离** — Worker 只能读取自己工作目录内的文件，写入限定在 `output/` 子目录
- **命令黑名单** — 禁止 `sudo`、`rm -rf /`、`fork bomb`、`curl | sh` 等危险操作
- **SSRF 防护** — HTTP 工具拦截内网地址（127.x / 10.x / 192.168.x / 元数据服务）
- **表达式注入防护** — 计算器使用纯 Java 递归下降解析器，不使用 eval/ScriptEngine
- **用后即焚** — Worker 执行完毕后立即删除工作目录，DNA 快照留存数据库可追溯

## License

MIT
