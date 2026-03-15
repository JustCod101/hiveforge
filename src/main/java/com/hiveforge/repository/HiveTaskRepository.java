package com.hiveforge.repository;

import com.hiveforge.manager.HiveTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class HiveTaskRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<HiveTask> ROW_MAPPER = (rs, rowNum) -> {
        HiveTask t = new HiveTask();
        t.setId(rs.getString("id"));
        t.setUserQuery(rs.getString("user_query"));
        t.setTaskPlan(rs.getString("task_plan"));
        t.setStatus(rs.getString("status"));
        t.setWorkerCount(rs.getInt("worker_count"));
        t.setTotalTokens(rs.getInt("total_tokens"));
        t.setTotalCostCents(rs.getInt("total_cost_cents"));
        t.setFinalReport(rs.getString("final_report"));
        t.setCreatedAt(rs.getString("created_at"));
        t.setCompletedAt(rs.getString("completed_at"));
        return t;
    };

    public HiveTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(HiveTask task) {
        jdbc.update("INSERT INTO hive_task (id, user_query, status) VALUES (?, ?, ?)",
                task.getId(), task.getUserQuery(), task.getStatus());
    }

    public void update(HiveTask task) {
        jdbc.update("""
            UPDATE hive_task SET task_plan = ?, status = ?, worker_count = ?,
                total_tokens = ?, total_cost_cents = ?, final_report = ?, completed_at = ?
            WHERE id = ?
            """,
                task.getTaskPlan(), task.getStatus(), task.getWorkerCount(),
                task.getTotalTokens(), task.getTotalCostCents(),
                task.getFinalReport(), task.getCompletedAt(), task.getId());
    }

    public HiveTask findById(String id) {
        List<HiveTask> results = jdbc.query(
                "SELECT * FROM hive_task WHERE id = ?", ROW_MAPPER, id);
        return results.isEmpty() ? null : results.get(0);
    }

    public List<HiveTask> findRecent(int offset, int limit) {
        return jdbc.query(
                "SELECT * FROM hive_task ORDER BY created_at DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, limit, offset);
    }
}
