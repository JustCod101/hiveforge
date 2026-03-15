-- ============================================================
-- HiveForge Database Schema (SQLite)
-- ============================================================

-- 1. Hive Task (one user request = one hive task)
CREATE TABLE IF NOT EXISTS hive_task (
    id              TEXT PRIMARY KEY,
    user_query      TEXT NOT NULL,
    task_plan       TEXT,
    status          TEXT NOT NULL DEFAULT 'PLANNING',
    worker_count    INTEGER DEFAULT 0,
    total_tokens    INTEGER DEFAULT 0,
    total_cost_cents INTEGER DEFAULT 0,
    final_report    TEXT,
    created_at      TEXT NOT NULL DEFAULT (datetime('now')),
    completed_at    TEXT
);

-- 2. Worker Agent lifecycle
CREATE TABLE IF NOT EXISTS worker_agent (
    id              TEXT PRIMARY KEY,
    hive_task_id    TEXT NOT NULL REFERENCES hive_task(id),
    worker_name     TEXT NOT NULL,
    worker_dir      TEXT NOT NULL,
    soul_md         TEXT NOT NULL,
    agents_md       TEXT NOT NULL,
    task_md         TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'SPAWNING',
    spawned_at      TEXT NOT NULL DEFAULT (datetime('now')),
    started_at      TEXT,
    completed_at    TEXT,
    destroyed_at    TEXT,
    result_text     TEXT,
    result_quality  REAL,
    tool_call_count INTEGER DEFAULT 0,
    llm_call_count  INTEGER DEFAULT 0,
    token_count     INTEGER DEFAULT 0,
    execution_ms    INTEGER DEFAULT 0,
    retry_count     INTEGER DEFAULT 0,
    error_message   TEXT
);

CREATE INDEX IF NOT EXISTS idx_worker_task ON worker_agent(hive_task_id);
CREATE INDEX IF NOT EXISTS idx_worker_status ON worker_agent(status);

-- 3. Worker execution trace (Thought-Action-Observation)
CREATE TABLE IF NOT EXISTS worker_trace (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    worker_id       TEXT NOT NULL REFERENCES worker_agent(id),
    step_index      INTEGER NOT NULL,
    step_type       TEXT NOT NULL,
    content         TEXT NOT NULL,
    tool_name       TEXT,
    tool_input      TEXT,
    tool_output     TEXT,
    latency_ms      INTEGER,
    created_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_trace_worker ON worker_trace(worker_id, step_index);

-- 4. DNA template library (reusable agent persona templates)
CREATE TABLE IF NOT EXISTS dna_template (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    template_name   TEXT NOT NULL UNIQUE,
    category        TEXT NOT NULL,
    soul_template   TEXT NOT NULL,
    agents_template TEXT NOT NULL,
    description     TEXT,
    usage_count     INTEGER DEFAULT 0,
    avg_quality     REAL DEFAULT 0,
    created_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

-- 5. Tool registry
CREATE TABLE IF NOT EXISTS tool_registry (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    tool_name       TEXT NOT NULL UNIQUE,
    description     TEXT NOT NULL,
    parameter_schema TEXT NOT NULL,
    permission_level TEXT DEFAULT 'SAFE',
    enabled         INTEGER DEFAULT 1
);
