package com.hiveforge.repository;

import com.hiveforge.trace.WorkerTrace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class WorkerTraceRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<WorkerTrace> ROW_MAPPER = (rs, rowNum) -> {
        WorkerTrace t = new WorkerTrace();
        t.setId(rs.getInt("id"));
        t.setWorkerId(rs.getString("worker_id"));
        t.setStepIndex(rs.getInt("step_index"));
        t.setStepType(rs.getString("step_type"));
        t.setContent(rs.getString("content"));
        t.setToolName(rs.getString("tool_name"));
        t.setToolInput(rs.getString("tool_input"));
        t.setToolOutput(rs.getString("tool_output"));
        t.setLatencyMs(rs.getObject("latency_ms") != null ? rs.getInt("latency_ms") : null);
        t.setCreatedAt(rs.getString("created_at"));
        return t;
    };

    public WorkerTraceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String workerId, int stepIndex, String stepType,
                     String content, String toolName, String toolInput, String toolOutput) {
        jdbc.update("""
            INSERT INTO worker_trace (worker_id, step_index, step_type, content,
                tool_name, tool_input, tool_output)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, workerId, stepIndex, stepType, content, toolName, toolInput, toolOutput);
    }

    /**
     * 保存带延迟统计的 Trace 记录
     */
    public void saveWithLatency(String workerId, int stepIndex, String stepType,
                                 String content, String toolName, String toolInput,
                                 String toolOutput, long latencyMs) {
        jdbc.update("""
            INSERT INTO worker_trace (worker_id, step_index, step_type, content,
                tool_name, tool_input, tool_output, latency_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, workerId, stepIndex, stepType, content, toolName, toolInput,
                toolOutput, latencyMs);
    }

    public List<WorkerTrace> findByWorkerId(String workerId) {
        return jdbc.query(
                "SELECT * FROM worker_trace WHERE worker_id = ? ORDER BY step_index",
                ROW_MAPPER, workerId);
    }
}
