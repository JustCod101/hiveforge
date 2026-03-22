package com.hiveforge.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class WorkerAgentRepository {

    private final JdbcTemplate jdbc;

    public WorkerAgentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String id, String hiveTaskId, String workerName,
                     String workerDir, String soulMd, String agentsMd, String taskMd) {
        jdbc.update("""
            INSERT INTO worker_agent (id, hive_task_id, worker_name, worker_dir,
                soul_md, agents_md, task_md, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'SPAWNING')
            """, id, hiveTaskId, workerName, workerDir, soulMd, agentsMd, taskMd);
    }

    public void updateStatus(String id, String status) {
        jdbc.update("UPDATE worker_agent SET status = ? WHERE id = ?", status, id);
    }

    public void updateStartedAt(String id, String startedAt) {
        jdbc.update("UPDATE worker_agent SET started_at = ? WHERE id = ?", startedAt, id);
    }

    public void updateDestroyedAt(String id, String destroyedAt) {
        jdbc.update("UPDATE worker_agent SET destroyed_at = ? WHERE id = ?", destroyedAt, id);
    }

    public void updateCompleted(String id, String resultText,
                                 int toolCallCount, int llmCallCount, long executionMs) {
        jdbc.update("""
            UPDATE worker_agent SET status = 'COMPLETED', result_text = ?,
                tool_call_count = ?, llm_call_count = ?, execution_ms = ?,
                completed_at = datetime('now')
            WHERE id = ?
            """, resultText, toolCallCount, llmCallCount, executionMs, id);
    }

    public void updateFailed(String id, String errorMessage, long executionMs) {
        jdbc.update("""
            UPDATE worker_agent SET status = 'FAILED', error_message = ?,
                execution_ms = ?, completed_at = datetime('now')
            WHERE id = ?
            """, errorMessage, executionMs, id);
    }

    public void updateResultQuality(String workerName, double quality) {
        jdbc.update(
                "UPDATE worker_agent SET result_quality = ? WHERE worker_name = ? AND result_quality IS NULL",
                quality, workerName);
    }

    public void updateResultQualityById(String workerId, double quality) {
        jdbc.update(
                "UPDATE worker_agent SET result_quality = ? WHERE id = ? AND result_quality IS NULL",
                quality, workerId);
    }

    public List<Map<String, Object>> findByTaskId(String taskId) {
        return jdbc.queryForList(
                "SELECT * FROM worker_agent WHERE hive_task_id = ? ORDER BY spawned_at",
                taskId);
    }

    public Map<String, Object> findById(String id) {
        List<Map<String, Object>> results = jdbc.queryForList(
                "SELECT * FROM worker_agent WHERE id = ?", id);
        return results.isEmpty() ? null : results.get(0);
    }
}
