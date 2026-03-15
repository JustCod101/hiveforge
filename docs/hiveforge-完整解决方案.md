# 动态派生与用后即焚 Agent 蜂群系统 — 完整解决方案设计

## 一、项目概述

**项目名称：** HiveForge — 基于 OpenClaw 架构的动态 Agent 蜂群引擎  
**技术定位：** 基于 SpringBoot 实现 "Markdown 即 Agent" 的动态派生与用后即焚多智能体系统  
**核心理念：** 文件系统即总线，LLM 编写 Agent DNA（SOUL.md / AGENTS.md / TASK.md），Manager 动态 fork Worker，任务完成后 rm -rf 销毁  
**简历价值：** 这个项目同时体现 Multi-Agent 编排、操作系统进程模型、文件系统设计、沙盒隔离、LLM 工程化五大能力

---

## 二、核心理念深度解析

### 2.1 范式对比：为什么 "Markdown 即 Agent" 优于传统方案

```
传统 Multi-Agent 框架:
┌─────────────────────────────────────────────┐
│  Agent 定义 = Python Class / JSON Config     │
│  ·类继承体系复杂                              │
│  ·配置修改需重启                              │
│  ·Agent 间状态耦合                            │
│  ·LLM 生成 JSON 常格式错误                    │
│  ·调试需要断点 / 日志                         │
└─────────────────────────────────────────────┘

OpenClaw "Markdown 即 Agent" 范式:
┌─────────────────────────────────────────────┐
│  Agent 定义 = 一个目录 + 几个 .md 文件        │
│                                              │
│  /agents/worker_researcher/                  │
│    ├── SOUL.md      ← 人格、行为准则          │
│    ├── AGENTS.md    ← 可用工具、能力声明       │
│    ├── TASK.md      ← 本次任务目标与边界       │
│    └── USER.md      ← 用户上下文画像          │
│                                              │
│  ·目录存在 = Agent 存活                       │
│  ·目录删除 = Agent 销毁                       │
│  ·打开文件 = 调试 Agent 灵魂                  │
│  ·LLM 生成 Markdown = 本能，几乎不出错        │
└─────────────────────────────────────────────┘
```

### 2.2 类比操作系统 fork() 模型

```
操作系统:                          HiveForge:
┌──────────┐                      ┌──────────────┐
│ 父进程    │  fork()              │ Manager Agent│  spawn()
│ PID=1    │ ─────→ 子进程         │              │ ─────→ Worker Agent
│          │        PID=2          │              │        /tmp/worker_xxx/
│          │        ·继承环境变量   │              │        ·继承 USER.md
│          │        ·独立地址空间   │              │        ·独立工作目录
│          │        ·exec() 执行   │              │        ·读取 SOUL.md 初始化
│          │        ·exit() 退出   │              │        ·完成后 rm -rf
└──────────┘        ·wait() 回收   └──────────────┘        ·结果回传 Manager
```

---

## 三、系统架构设计

### 3.1 整体架构

```
┌──────────────────────────────────────────────────────────────────────┐
│                        接入层 Access Layer                           │
│  REST API · WebSocket · SSE 实时推送 · 管理面板                       │
├──────────────────────────────────────────────────────────────────────┤
│                    Manager Agent 层 (常驻)                           │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │                   Manager Agent Engine                         │ │
│  │  ·接收用户复杂任务                                              │ │
│  │  ·任务分析 & 分解 (Task Decomposition)                         │ │
│  │  ·Worker DNA 规划 (决定每个 Worker 需要什么人格/工具)            │ │
│  │  ·LLM 生成 SOUL.md / AGENTS.md / TASK.md                     │ │
│  │  ·Worker 生命周期管理 (Spawn → Monitor → Collect → Destroy)    │ │
│  │  ·结果聚合 & 最终报告生成                                      │ │
│  └────────────────────────┬───────────────────────────────────────┘ │
│                           │ spawn()                                  │
├───────────────────────────┼──────────────────────────────────────────┤
│                 Worker Agent 层 (用后即焚)                            │
│                           │                                          │
│    ┌──────────┐    ┌──────▼──────┐    ┌──────────────┐              │
│    │ Worker A │    │ Worker B    │    │ Worker C     │   ...        │
│    │ 财务分析师│    │ 竞品调研员   │    │ 报告撰写专家 │              │
│    │          │    │             │    │              │              │
│    │ /tmp/    │    │ /tmp/       │    │ /tmp/        │              │
│    │ w_fin/   │    │ w_research/ │    │ w_report/    │              │
│    │ SOUL.md  │    │ SOUL.md     │    │ SOUL.md      │              │
│    │ AGENTS.md│    │ AGENTS.md   │    │ AGENTS.md    │              │
│    │ TASK.md  │    │ TASK.md     │    │ TASK.md      │              │
│    └──┬───────┘    └──┬──────────┘    └──┬───────────┘              │
│       │ result         │ result           │ result                   │
│       └────────────────┴──────────────────┘                         │
│                           │                                          │
├───────────────────────────┼──────────────────────────────────────────┤
│                     工具层 Tool Layer                                 │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ │
│  │write_file│ │read_file │ │web_search│ │shell_exec│ │http_call │ │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘ │
├──────────────────────────────────────────────────────────────────────┤
│                     可观测层 Observability                            │
│  Worker 生命周期追踪 · DNA 快照 · 执行 Trace · 蜂群状态仪表板         │
├──────────────────────────────────────────────────────────────────────┤
│                     存储层 Storage                                    │
│  文件系统 (Agent DNA) · SQLite (任务/Trace) · Markdown (记忆/结果)    │
└──────────────────────────────────────────────────────────────────────┘
```

### 3.2 核心技术栈

| 层级 | 技术选型 | 用途 |
|------|---------|------|
| 核心框架 | SpringBoot 3.2 + JDK 17 | 服务基座 |
| LLM 调用 | LangChain4j / OpenAI SDK | Manager & Worker 推理 |
| Agent DNA | Markdown 文件 (SOUL/AGENTS/TASK/USER.md) | Agent 定义与生命 |
| 文件系统 | Java NIO (WatchService + Files API) | Agent 目录管理、文件监听 |
| 进程隔离 | 独立线程池 + 工作目录隔离 | Worker 逻辑隔离 |
| Tool 执行 | ProcessBuilder + HttpClient + Jsoup | 沙盒工具链 |
| 任务调度 | CompletableFuture + 虚拟线程 | Worker 并行/串行编排 |
| 持久化 | SQLite | 任务记录、Trace、DNA 快照 |
| 流式输出 | SSE (Server-Sent Events) | 蜂群执行实时推送 |
| 可视化 | Thymeleaf + HTMX / React | 蜂群状态仪表板 |
| 容器化 | Docker + Docker Compose | 部署 |

---

## 四、数据库建模

```sql
-- ============================================================
-- HiveForge 数据库 Schema (SQLite)
-- ============================================================

-- 1. 蜂群任务（一次用户请求 = 一个蜂群任务）
CREATE TABLE hive_task (
    id              TEXT PRIMARY KEY,           -- UUID
    user_query      TEXT NOT NULL,              -- 用户原始请求
    task_plan       TEXT,                       -- Manager 生成的任务计划 (JSON)
    status          TEXT NOT NULL DEFAULT 'PLANNING',
    -- PLANNING → SPAWNING → EXECUTING → AGGREGATING → COMPLETED → FAILED
    worker_count    INTEGER DEFAULT 0,
    total_tokens    INTEGER DEFAULT 0,
    total_cost_cents INTEGER DEFAULT 0,
    final_report    TEXT,                       -- 最终聚合报告 (Markdown)
    created_at      TEXT NOT NULL DEFAULT (datetime('now')),
    completed_at    TEXT
);

-- 2. Worker Agent 生命周期
CREATE TABLE worker_agent (
    id              TEXT PRIMARY KEY,           -- UUID
    hive_task_id    TEXT NOT NULL REFERENCES hive_task(id),
    worker_name     TEXT NOT NULL,              -- "financial_analyst", "researcher"
    worker_dir      TEXT NOT NULL,              -- 工作目录路径 /tmp/hive/w_xxx/
    
    -- DNA 快照（即使目录被删，DNA 仍可追溯）
    soul_md         TEXT NOT NULL,              -- SOUL.md 内容快照
    agents_md       TEXT NOT NULL,              -- AGENTS.md 内容快照
    task_md         TEXT NOT NULL,              -- TASK.md 内容快照
    
    -- 生命周期
    status          TEXT NOT NULL DEFAULT 'SPAWNING',
    -- SPAWNING → INITIALIZING → EXECUTING → COMPLETED → DESTROYED → FAILED
    spawned_at      TEXT NOT NULL DEFAULT (datetime('now')),
    started_at      TEXT,
    completed_at    TEXT,
    destroyed_at    TEXT,
    
    -- 结果
    result_text     TEXT,                       -- Worker 输出的结果
    result_quality  REAL,                       -- Manager 对结果的评分 0-1
    
    -- 统计
    tool_call_count INTEGER DEFAULT 0,
    llm_call_count  INTEGER DEFAULT 0,
    token_count     INTEGER DEFAULT 0,
    execution_ms    INTEGER DEFAULT 0,
    retry_count     INTEGER DEFAULT 0,
    error_message   TEXT
);

CREATE INDEX idx_worker_task ON worker_agent(hive_task_id);
CREATE INDEX idx_worker_status ON worker_agent(status);

-- 3. Worker 执行 Trace（Thought-Action-Observation）
CREATE TABLE worker_trace (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    worker_id       TEXT NOT NULL REFERENCES worker_agent(id),
    step_index      INTEGER NOT NULL,
    step_type       TEXT NOT NULL,              -- THOUGHT / ACTION / OBSERVATION / REFLECTION
    content         TEXT NOT NULL,
    tool_name       TEXT,
    tool_input      TEXT,                       -- JSON
    tool_output     TEXT,
    latency_ms      INTEGER,
    created_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_trace_worker ON worker_trace(worker_id, step_index);

-- 4. DNA 模板库（可复用的 Agent 人格模板）
CREATE TABLE dna_template (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    template_name   TEXT NOT NULL UNIQUE,       -- "financial_analyst", "code_reviewer"
    category        TEXT NOT NULL,              -- RESEARCH / ANALYSIS / WRITING / CODING
    soul_template   TEXT NOT NULL,              -- SOUL.md 模板（可含 {{变量}}）
    agents_template TEXT NOT NULL,              -- AGENTS.md 模板
    description     TEXT,
    usage_count     INTEGER DEFAULT 0,
    avg_quality     REAL DEFAULT 0,
    created_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

-- 5. 工具注册表
CREATE TABLE tool_registry (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    tool_name       TEXT NOT NULL UNIQUE,
    description     TEXT NOT NULL,
    parameter_schema TEXT NOT NULL,             -- JSON Schema
    permission_level TEXT DEFAULT 'SAFE',       -- SAFE / MODERATE / DANGEROUS
    enabled         INTEGER DEFAULT 1
);
```

---

## 五、核心模块详细设计

### 5.1 Agent DNA 文件规范

```markdown
# ===== SOUL.md 示例 =====
# 人格与行为准则

## 身份
你是一名资深的财务分析师，专注于科技公司的财务报表分析。
你有 15 年的投行经验，擅长发现财务数据中的异常信号。

## 行为准则
- 所有结论必须有数据支撑，不得臆测
- 发现风险时用 ⚠️ 标记并量化影响
- 输出格式为结构化 Markdown 表格
- 如果信息不足，明确说明需要什么额外数据

## 输出格式
以 Markdown 格式输出分析报告，包含：
1. 核心指标摘要表
2. 同比/环比变化分析
3. 风险信号标注
4. 结论与建议
```

```markdown
# ===== AGENTS.md 示例 =====
# 能力与工具声明

## 可用工具
- **web_search**: 搜索互联网获取公开财务数据
- **file_read**: 读取本地文件（如已下载的年报 PDF 文本）
- **file_write**: 将分析结果写入文件
- **calculate**: 执行数学计算（财务比率等）

## 工具使用规则
- 每次搜索前先思考最优的搜索关键词
- 读取文件前先确认文件路径是否存在
- 计算时显示完整公式
```

```markdown
# ===== TASK.md 示例 =====
# 本次任务

## 目标
分析 Apple (AAPL) 2024 年 Q4 财报，输出关键财务指标分析报告。

## 具体要求
1. 搜索并获取 AAPL 2024 Q4 的营收、净利润、毛利率、EPS
2. 与 Q3 和去年同期对比
3. 分析 Services 业务占比趋势
4. 识别潜在风险信号

## 交付物
将最终报告写入 `./output/report.md`

## 约束
- 最多使用 10 次工具调用
- 30 秒内完成
- 如果搜索不到数据，记录缺失项并标注
```

### 5.2 Manager Agent — 蜂群指挥官

```java
/**
 * Manager Agent — 蜂群任务编排核心
 * 
 * 职责:
 * 1. 接收用户复杂任务
 * 2. 任务分解 → 决定需要哪些 Worker
 * 3. 为每个 Worker 规划 DNA（人格 + 工具 + 任务边界）
 * 4. 调用 LLM 生成 SOUL.md / AGENTS.md / TASK.md
 * 5. Spawn Workers → 监控执行 → 收集结果
 * 6. 聚合所有 Worker 结果 → 生成最终报告
 * 7. 销毁所有 Worker 目录（用后即焚）
 */
@Service
public class ManagerAgent {

    @Autowired private LlmClient llmClient;
    @Autowired private WorkerSpawner workerSpawner;
    @Autowired private WorkerMonitor workerMonitor;
    @Autowired private ResultAggregator resultAggregator;
    @Autowired private HiveTaskRepository taskRepo;
    @Autowired private DnaTemplateRepository templateRepo;

    private static final String PLANNING_PROMPT = """
        你是一个任务规划专家。分析用户的复杂任务，将其分解为多个专业子任务，
        每个子任务由一个独立的 Worker Agent 执行。
        
        ## 可用的 Worker 类型模板
        {templates}
        
        ## 用户任务
        {user_query}
        
        ## 输出格式（严格 JSON）
        {
          "task_summary": "任务概述",
          "workers": [
            {
              "name": "worker 名称（英文，如 financial_analyst）",
              "role": "角色描述",
              "tools_needed": ["web_search", "calculate"],
              "task_description": "该 Worker 的具体任务",
              "depends_on": [],
              "priority": 1,
              "template": "模板名或 null（表示自动生成）"
            }
          ],
          "execution_strategy": "parallel 或 sequential 或 dag",
          "aggregation_instruction": "如何将各 Worker 结果合并为最终报告"
        }
    """;

    /**
     * 执行蜂群任务的主入口
     */
    public Mono<HiveResult> executeHive(String userQuery, 
                                         StreamCallback callback) {
        String taskId = UUID.randomUUID().toString();
        HiveTask task = new HiveTask(taskId, userQuery);
        taskRepo.save(task);

        return Mono.fromCallable(() -> {
            callback.onEvent("hive_start", "蜂群任务启动: " + taskId);

            // ===== Phase 1: 任务规划 =====
            callback.onEvent("planning", "Manager 正在分析任务并规划 Worker...");
            task.setStatus("PLANNING");
            
            TaskPlan plan = planTask(userQuery);
            task.setTaskPlan(toJson(plan));
            task.setWorkerCount(plan.getWorkers().size());
            taskRepo.update(task);
            
            callback.onEvent("plan_ready", 
                "任务已分解为 " + plan.getWorkers().size() + " 个 Worker");

            // ===== Phase 2: 孕育 Workers =====
            callback.onEvent("spawning", "正在生成 Worker DNA...");
            task.setStatus("SPAWNING");
            
            List<SpawnedWorker> workers = new ArrayList<>();
            for (WorkerPlan wp : plan.getWorkers()) {
                SpawnedWorker worker = spawnWorker(taskId, wp, userQuery);
                workers.add(worker);
                
                callback.onEvent("worker_spawned", String.format(
                    "🧬 Worker [%s] 已孕育 — %s", 
                    worker.getName(), worker.getDir()));
            }

            // ===== Phase 3: 执行 =====
            callback.onEvent("executing", "Workers 开始执行任务...");
            task.setStatus("EXECUTING");
            
            List<WorkerResult> results = executeWorkers(
                workers, plan.getExecutionStrategy(), callback);

            // ===== Phase 4: 聚合 =====
            callback.onEvent("aggregating", "Manager 正在汇总所有 Worker 结果...");
            task.setStatus("AGGREGATING");
            
            String finalReport = resultAggregator.aggregate(
                userQuery, plan, results, callback);

            // ===== Phase 5: 销毁 =====
            callback.onEvent("destroying", "清理 Worker 目录（用后即焚）...");
            for (SpawnedWorker worker : workers) {
                destroyWorker(worker, callback);
            }

            // 完成
            task.setStatus("COMPLETED");
            task.setFinalReport(finalReport);
            task.setCompletedAt(Instant.now().toString());
            taskRepo.update(task);
            
            callback.onEvent("hive_complete", "蜂群任务完成");

            return new HiveResult(taskId, finalReport, results);
            
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Phase 1: 任务规划 — LLM 分解复杂任务
     */
    private TaskPlan planTask(String userQuery) {
        // 获取可用的 DNA 模板
        List<DnaTemplate> templates = templateRepo.findAll();
        String templateDesc = templates.stream()
            .map(t -> "- " + t.getTemplateName() + ": " + t.getDescription())
            .collect(Collectors.joining("\n"));

        String prompt = PLANNING_PROMPT
            .replace("{templates}", templateDesc)
            .replace("{user_query}", userQuery);

        String response = llmClient.chat("gpt-4o", prompt);
        return parseTaskPlan(response);
    }

    /**
     * Phase 2: 孕育 Worker — 生成 DNA 文件到临时目录
     */
    private SpawnedWorker spawnWorker(String taskId, WorkerPlan plan, 
                                      String userContext) {
        return workerSpawner.spawn(taskId, plan, userContext);
    }

    /**
     * Phase 3: 按策略执行所有 Workers
     */
    private List<WorkerResult> executeWorkers(
            List<SpawnedWorker> workers, String strategy,
            StreamCallback callback) {
        
        return switch (strategy) {
            case "parallel" -> executeParallel(workers, callback);
            case "sequential" -> executeSequential(workers, callback);
            case "dag" -> executeDAG(workers, callback);
            default -> executeParallel(workers, callback);
        };
    }

    /**
     * 并行执行所有 Workers
     */
    private List<WorkerResult> executeParallel(
            List<SpawnedWorker> workers, StreamCallback callback) {
        List<CompletableFuture<WorkerResult>> futures = workers.stream()
            .map(worker -> CompletableFuture.supplyAsync(() -> {
                callback.onEvent("worker_start", 
                    "⚡ [" + worker.getName() + "] 开始执行");
                
                WorkerResult result = workerMonitor.executeAndMonitor(worker);
                
                callback.onEvent("worker_done",
                    (result.isSuccess() ? "✅" : "❌") + " [" 
                    + worker.getName() + "] " 
                    + (result.isSuccess() ? "完成" : "失败: " + result.getError()));
                
                return result;
            }))
            .toList();

        return futures.stream()
            .map(CompletableFuture::join)
            .toList();
    }

    /**
     * Phase 5: 销毁 Worker — rm -rf 工作目录
     */
    private void destroyWorker(SpawnedWorker worker, StreamCallback callback) {
        try {
            Path dir = Path.of(worker.getDir());
            if (Files.exists(dir)) {
                // 递归删除
                Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
                
                // 更新状态
                workerAgentRepo.updateStatus(worker.getId(), "DESTROYED");
                workerAgentRepo.updateDestroyedAt(worker.getId(), 
                    Instant.now().toString());
                
                callback.onEvent("worker_destroyed",
                    "💀 [" + worker.getName() + "] 已销毁");
            }
        } catch (IOException e) {
            log.error("Failed to destroy worker dir: {}", worker.getDir(), e);
        }
    }
}
```

### 5.3 Worker Spawner — DNA 生成器

```java
/**
 * Worker Spawner — Worker Agent DNA 生成器
 * 
 * 核心流程:
 * 1. 创建临时工作目录 /tmp/hive/{taskId}/{workerName}/
 * 2. 查找匹配的 DNA 模板，或让 LLM 从零生成
 * 3. LLM 生成 SOUL.md (人格) + AGENTS.md (工具) + TASK.md (任务)
 * 4. 写入文件系统 → Worker 诞生
 */
@Service
public class WorkerSpawner {

    @Autowired private LlmClient llmClient;
    @Autowired private DnaTemplateRepository templateRepo;
    @Autowired private WorkerAgentRepository workerRepo;
    @Autowired private ToolRegistry toolRegistry;

    private static final Path HIVE_BASE = Path.of("/tmp/hive");

    private static final String DNA_GENERATION_PROMPT = """
        你是一个 Agent 架构师。你需要为一个 Worker Agent 编写 DNA 文件。
        
        ## Worker 信息
        - 角色: {role}
        - 可用工具: {tools}
        - 任务: {task}
        - 用户上下文: {context}
        
        ## 输出要求
        请分别生成三个 Markdown 文件的内容。用 === 分隔：
        
        === SOUL.md ===
        (定义 Worker 的人格、专业背景、行为准则、输出格式要求)
        
        === AGENTS.md ===
        (声明可用工具及使用规则，每个工具的适用场景)
        
        === TASK.md ===
        (明确本次任务目标、具体步骤、交付物、约束条件)
        
        要求:
        - SOUL.md 要赋予 Worker 鲜明的专业人格
        - AGENTS.md 只声明该 Worker 真正需要的工具
        - TASK.md 要非常具体，包含可衡量的完成标准
    """;

    /**
     * 孕育一个 Worker Agent
     */
    public SpawnedWorker spawn(String taskId, WorkerPlan plan, 
                                String userContext) {
        String workerId = UUID.randomUUID().toString().substring(0, 8);
        String workerName = plan.getName();
        
        // 1. 创建工作目录
        Path workerDir = HIVE_BASE.resolve(taskId).resolve(workerName + "_" + workerId);
        createDirectoryStructure(workerDir);

        // 2. 生成 DNA
        AgentDna dna;
        if (plan.getTemplate() != null) {
            // 使用预定义模板 + 变量填充
            dna = generateFromTemplate(plan, userContext);
        } else {
            // LLM 从零生成
            dna = generateFromScratch(plan, userContext);
        }

        // 3. 写入文件系统 → Agent 诞生！
        writeFile(workerDir.resolve("SOUL.md"), dna.getSoulMd());
        writeFile(workerDir.resolve("AGENTS.md"), dna.getAgentsMd());
        writeFile(workerDir.resolve("TASK.md"), dna.getTaskMd());
        
        // 如果有用户上下文，写入 USER.md
        if (userContext != null && !userContext.isEmpty()) {
            writeFile(workerDir.resolve("USER.md"), 
                "# 用户上下文\n\n" + userContext);
        }
        
        // 创建 output 目录
        createDirectoryStructure(workerDir.resolve("output"));

        // 4. 持久化 Worker 记录 + DNA 快照
        WorkerAgent agent = WorkerAgent.builder()
            .id(workerId)
            .hiveTaskId(taskId)
            .workerName(workerName)
            .workerDir(workerDir.toString())
            .soulMd(dna.getSoulMd())
            .agentsMd(dna.getAgentsMd())
            .taskMd(dna.getTaskMd())
            .status("SPAWNING")
            .build();
        workerRepo.save(agent);

        log.info("[Spawner] Worker born: {} at {}", workerName, workerDir);
        
        return new SpawnedWorker(workerId, workerName, workerDir.toString(), 
                                 dna, plan.getToolsNeeded());
    }

    /**
     * LLM 从零生成 DNA
     */
    private AgentDna generateFromScratch(WorkerPlan plan, String userContext) {
        // 获取工具描述
        String toolsDesc = plan.getToolsNeeded().stream()
            .map(name -> toolRegistry.getDescription(name))
            .collect(Collectors.joining("\n"));

        String prompt = DNA_GENERATION_PROMPT
            .replace("{role}", plan.getRole())
            .replace("{tools}", toolsDesc)
            .replace("{task}", plan.getTaskDescription())
            .replace("{context}", userContext != null ? userContext : "无");

        String response = llmClient.chat("gpt-4o", prompt);
        return parseDna(response);
    }

    /**
     * 基于模板生成 DNA（模板变量替换）
     */
    private AgentDna generateFromTemplate(WorkerPlan plan, String userContext) {
        DnaTemplate template = templateRepo.findByName(plan.getTemplate());
        
        String soul = template.getSoulTemplate()
            .replace("{{task}}", plan.getTaskDescription())
            .replace("{{context}}", userContext != null ? userContext : "");
        
        String agents = template.getAgentsTemplate()
            .replace("{{tools}}", String.join(", ", plan.getToolsNeeded()));
        
        // TASK.md 始终由 LLM 针对具体任务生成
        String taskMd = generateTaskMd(plan);
        
        return new AgentDna(soul, agents, taskMd);
    }

    /**
     * 解析 LLM 生成的 DNA（按 === 分隔符切割）
     */
    private AgentDna parseDna(String response) {
        String[] parts = response.split("===\\s*\\w+\\.md\\s*===");
        
        String soul = parts.length > 1 ? parts[1].trim() : "# Default Soul\n你是一个通用助手。";
        String agents = parts.length > 2 ? parts[2].trim() : "# Default Agents\n无特殊工具。";
        String task = parts.length > 3 ? parts[3].trim() : "# Default Task\n完成用户交代的任务。";
        
        return new AgentDna(soul, agents, task);
    }
}
```

### 5.4 Worker 执行引擎 — 读取 DNA 并执行

```java
/**
 * Worker Agent Engine — Worker 的"灵魂载入"与执行引擎
 * 
 * 核心流程:
 * 1. 读取工作目录中的 SOUL.md / AGENTS.md / TASK.md
 * 2. 组装为 System Prompt → Worker 初始化完成
 * 3. ReAct 循环执行任务（使用 AGENTS.md 声明的工具）
 * 4. 结果写入 output/ 目录
 * 5. 返回结果给 Manager
 */
@Service
public class WorkerEngine {

    @Autowired private LlmClient llmClient;
    @Autowired private ToolExecutor toolExecutor;
    @Autowired private WorkerTraceRepository traceRepo;
    @Autowired private WorkerAgentRepository workerRepo;

    private static final int MAX_TOOL_ROUNDS = 10;

    /**
     * 执行一个 Worker Agent
     */
    public WorkerResult execute(SpawnedWorker worker) {
        Path workerDir = Path.of(worker.getDir());
        long startTime = System.nanoTime();
        
        try {
            // ===== 1. 读取 DNA → 灵魂载入 =====
            String soulMd = readFile(workerDir.resolve("SOUL.md"));
            String agentsMd = readFile(workerDir.resolve("AGENTS.md"));
            String taskMd = readFile(workerDir.resolve("TASK.md"));
            String userMd = readFileIfExists(workerDir.resolve("USER.md"));
            
            workerRepo.updateStatus(worker.getId(), "INITIALIZING");
            
            // ===== 2. 组装 System Prompt =====
            String systemPrompt = assembleSystemPrompt(
                soulMd, agentsMd, taskMd, userMd, workerDir);
            
            // ===== 3. 准备工具列表（只注册 AGENTS.md 声明的工具） =====
            List<ToolDefinition> tools = worker.getToolsNeeded().stream()
                .map(toolExecutor::getDefinition)
                .filter(Objects::nonNull)
                .toList();

            // ===== 4. ReAct 循环 =====
            workerRepo.updateStatus(worker.getId(), "EXECUTING");
            workerRepo.updateStartedAt(worker.getId(), Instant.now().toString());
            
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system(systemPrompt));
            messages.add(ChatMessage.user("请开始执行 TASK.md 中描述的任务。"));
            
            StringBuilder fullOutput = new StringBuilder();
            int toolCalls = 0;
            int llmCalls = 0;
            int stepIndex = 0;

            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                llmCalls++;
                
                // 调用 LLM
                ChatResponse response = llmClient.chatWithTools(
                    "gpt-4o-mini", messages, tools);
                
                String content = response.getContent();
                List<ToolCall> calls = response.getToolCalls();

                // 记录 Thought
                if (content != null && !content.isEmpty()) {
                    traceRepo.save(WorkerTrace.thought(
                        worker.getId(), ++stepIndex, content));
                    fullOutput.append(content).append("\n");
                }

                // 无 Tool Call → 任务完成
                if (calls == null || calls.isEmpty()) {
                    break;
                }

                // 执行 Tool Calls
                messages.add(ChatMessage.assistant(content, calls));
                
                for (ToolCall call : calls) {
                    toolCalls++;
                    
                    traceRepo.save(WorkerTrace.action(
                        worker.getId(), ++stepIndex, 
                        call.getName(), call.getArguments()));
                    
                    // 工具执行上下文限定在 Worker 工作目录内
                    ToolResult result = toolExecutor.executeInContext(
                        call, workerDir.toString());
                    
                    traceRepo.save(WorkerTrace.observation(
                        worker.getId(), ++stepIndex, 
                        result.getOutput()));
                    
                    messages.add(ChatMessage.toolResult(
                        call.getId(), result.getOutput()));
                }
            }

            // ===== 5. 读取 output/ 目录中的结果文件 =====
            String outputResult = readOutputFiles(workerDir.resolve("output"));
            if (!outputResult.isEmpty()) {
                fullOutput.append("\n\n---\n").append(outputResult);
            }

            // 更新状态
            long executionMs = (System.nanoTime() - startTime) / 1_000_000;
            workerRepo.updateCompleted(worker.getId(), 
                fullOutput.toString(), toolCalls, llmCalls, executionMs);

            return WorkerResult.success(worker.getName(), fullOutput.toString());
            
        } catch (Exception e) {
            long executionMs = (System.nanoTime() - startTime) / 1_000_000;
            workerRepo.updateFailed(worker.getId(), e.getMessage(), executionMs);
            return WorkerResult.failure(worker.getName(), e.getMessage());
        }
    }

    /**
     * 组装 System Prompt — 将 DNA 文件融合为完整指令
     */
    private String assembleSystemPrompt(String soul, String agents, 
                                         String task, String user,
                                         Path workerDir) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("# 你的身份与人格\n\n").append(soul).append("\n\n");
        sb.append("# 你的能力与工具\n\n").append(agents).append("\n\n");
        sb.append("# 你的任务\n\n").append(task).append("\n\n");
        
        if (user != null) {
            sb.append("# 用户背景\n\n").append(user).append("\n\n");
        }
        
        sb.append("# 工作规则\n\n");
        sb.append("- 你的工作目录是: ").append(workerDir).append("\n");
        sb.append("- 将最终成果写入 ./output/ 目录\n");
        sb.append("- 完成任务后，输出一段简洁的结果摘要\n");
        sb.append("- 如果遇到困难，在输出中说明原因\n");
        
        return sb.toString();
    }

    /**
     * 读取 output/ 目录中的所有结果文件
     */
    private String readOutputFiles(Path outputDir) {
        if (!Files.exists(outputDir)) return "";
        
        try (var paths = Files.walk(outputDir, 1)) {
            return paths
                .filter(Files::isRegularFile)
                .map(p -> {
                    try {
                        return "### " + p.getFileName() + "\n\n" 
                               + Files.readString(p);
                    } catch (IOException e) { return ""; }
                })
                .collect(Collectors.joining("\n\n"));
        } catch (IOException e) {
            return "";
        }
    }
}
```

### 5.5 结果聚合器 — Manager 的最终判断

```java
/**
 * Result Aggregator — 将多个 Worker 的结果汇总为最终报告
 */
@Service
public class ResultAggregator {

    @Autowired private LlmClient llmClient;

    private static final String AGGREGATE_PROMPT = """
        你是一个报告总编辑。多个专业团队成员已完成各自的子任务，
        你需要将他们的成果汇总为一份高质量的最终报告。
        
        ## 原始任务
        {user_query}
        
        ## 汇总策略
        {aggregation_instruction}
        
        ## 各 Worker 的成果
        {worker_results}
        
        ## 要求
        1. 去除重复信息，保留最有价值的分析
        2. 发现不同 Worker 之间的矛盾时，标注并给出你的判断
        3. 输出一份结构清晰的 Markdown 报告
        4. 报告末尾给出整体置信度评分 (1-10)
    """;

    public String aggregate(String userQuery, TaskPlan plan,
                            List<WorkerResult> results,
                            StreamCallback callback) {
        
        // 构建各 Worker 结果摘要
        StringBuilder workerResults = new StringBuilder();
        for (WorkerResult r : results) {
            workerResults.append("### Worker: ").append(r.getWorkerName())
                .append(r.isSuccess() ? " ✅" : " ❌").append("\n\n");
            
            if (r.isSuccess()) {
                // 截断过长的结果
                String output = r.getOutput();
                if (output.length() > 3000) {
                    output = output.substring(0, 3000) + "\n... (已截断)";
                }
                workerResults.append(output);
            } else {
                workerResults.append("执行失败: ").append(r.getError());
            }
            workerResults.append("\n\n---\n\n");
        }

        String prompt = AGGREGATE_PROMPT
            .replace("{user_query}", userQuery)
            .replace("{aggregation_instruction}", 
                plan.getAggregationInstruction())
            .replace("{worker_results}", workerResults.toString());

        callback.onEvent("aggregating_llm", "Manager LLM 正在撰写最终报告...");
        
        return llmClient.chat("gpt-4o", prompt);
    }
}
```

### 5.6 蜂群可观测性 — 全链路追踪 + SSE 实时推送

```java
/**
 * Hive Controller — 蜂群任务 API + SSE 实时推送
 */
@RestController
@RequestMapping("/api/v1/hive")
public class HiveController {

    @Autowired private ManagerAgent managerAgent;
    @Autowired private HiveTaskRepository taskRepo;
    @Autowired private WorkerAgentRepository workerRepo;
    @Autowired private WorkerTraceRepository traceRepo;

    /**
     * 启动蜂群任务（SSE 实时推送整个过程）
     */
    @GetMapping(value = "/execute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter execute(@RequestParam String query) {
        SseEmitter emitter = new SseEmitter(600_000L); // 10 分钟
        
        managerAgent.executeHive(query, new StreamCallback() {
            @Override
            public void onEvent(String type, String message) {
                try {
                    emitter.send(SseEmitter.event()
                        .name(type)
                        .data(Map.of(
                            "type", type,
                            "message", message,
                            "timestamp", Instant.now().toString()
                        )));
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            }
        }).subscribe(
            result -> {
                try {
                    emitter.send(SseEmitter.event()
                        .name("final_report")
                        .data(result.getReport()));
                    emitter.complete();
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            },
            emitter::completeWithError
        );
        
        return emitter;
    }

    /**
     * 查看蜂群任务状态
     */
    @GetMapping("/{taskId}")
    public HiveTask getTask(@PathVariable String taskId) {
        return taskRepo.findById(taskId);
    }

    /**
     * 查看所有 Worker 及其 DNA 快照
     */
    @GetMapping("/{taskId}/workers")
    public List<WorkerAgent> getWorkers(@PathVariable String taskId) {
        return workerRepo.findByTaskId(taskId);
    }

    /**
     * 查看某个 Worker 的完整执行 Trace
     */
    @GetMapping("/workers/{workerId}/trace")
    public List<WorkerTrace> getWorkerTrace(@PathVariable String workerId) {
        return traceRepo.findByWorkerId(workerId);
    }

    /**
     * 查看某个 Worker 的 DNA 快照（即使已被销毁）
     */
    @GetMapping("/workers/{workerId}/dna")
    public Map<String, String> getWorkerDna(@PathVariable String workerId) {
        WorkerAgent w = workerRepo.findById(workerId);
        return Map.of(
            "SOUL.md", w.getSoulMd(),
            "AGENTS.md", w.getAgentsMd(),
            "TASK.md", w.getTaskMd()
        );
    }

    /**
     * 蜂群历史任务列表
     */
    @GetMapping("/history")
    public List<HiveTask> getHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return taskRepo.findRecent(page * size, size);
    }
}
```

---

## 六、项目目录结构

```
hiveforge/
├── docker-compose.yml
├── pom.xml
├── README.md
│
├── templates/                              # DNA 模板库
│   ├── financial_analyst/
│   │   ├── SOUL.template.md
│   │   └── AGENTS.template.md
│   ├── code_reviewer/
│   │   ├── SOUL.template.md
│   │   └── AGENTS.template.md
│   └── researcher/
│       ├── SOUL.template.md
│       └── AGENTS.template.md
│
├── sql/
│   └── schema.sql
│
├── src/main/java/com/hiveforge/
│   ├── HiveForgeApplication.java
│   │
│   ├── manager/                            # Manager Agent
│   │   ├── ManagerAgent.java               # 蜂群指挥官
│   │   ├── TaskPlan.java                   # 任务计划模型
│   │   └── ResultAggregator.java           # 结果聚合器
│   │
│   ├── spawner/                            # Worker 孕育
│   │   ├── WorkerSpawner.java              # DNA 生成器
│   │   ├── AgentDna.java                   # DNA 数据模型
│   │   └── SpawnedWorker.java
│   │
│   ├── worker/                             # Worker 执行引擎
│   │   ├── WorkerEngine.java               # 灵魂载入 + ReAct 执行
│   │   ├── WorkerMonitor.java              # 执行监控
│   │   └── WorkerResult.java
│   │
│   ├── tool/                               # 工具层
│   │   ├── ToolExecutor.java
│   │   ├── ToolRegistry.java
│   │   ├── builtin/
│   │   │   ├── WebSearchTool.java
│   │   │   ├── FileReadTool.java
│   │   │   ├── FileWriteTool.java
│   │   │   ├── ShellExecuteTool.java
│   │   │   ├── HttpCallTool.java
│   │   │   └── CalculateTool.java
│   │   └── ToolResult.java
│   │
│   ├── trace/                              # 可观测
│   │   ├── WorkerTrace.java
│   │   └── TraceService.java
│   │
│   ├── llm/                                # LLM 客户端
│   │   ├── LlmClient.java
│   │   └── StreamCallback.java
│   │
│   ├── controller/
│   │   ├── HiveController.java             # 蜂群任务 API
│   │   └── DashboardController.java        # 蜂群仪表板
│   │
│   ├── domain/
│   │   └── entity/
│   │
│   └── repository/
│       ├── HiveTaskRepository.java
│       ├── WorkerAgentRepository.java
│       ├── WorkerTraceRepository.java
│       └── DnaTemplateRepository.java
│
├── src/main/resources/
│   ├── application.yml
│   ├── schema.sql
│   └── templates/
│       └── dashboard.html                  # 蜂群仪表板
│
└── tests/
```

