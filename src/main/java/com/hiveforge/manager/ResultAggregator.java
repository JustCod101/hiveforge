package com.hiveforge.manager;

import com.hiveforge.llm.LlmClient;
import com.hiveforge.llm.StreamCallback;
import com.hiveforge.repository.WorkerAgentRepository;
import com.hiveforge.worker.WorkerResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Result Aggregator — 将多个 Worker 的结果汇总为最终报告。
 *
 * 职责：
 * 1. 收集所有 Worker 的执行结果
 * 2. 构建结构化的汇总 Prompt，包含成功/失败状态
 * 3. 调用 LLM 生成最终报告
 * 4. 提取 LLM 对每个 Worker 的质量评分，回写数据库
 */
@Service
public class ResultAggregator {

    private static final Logger log = LoggerFactory.getLogger(ResultAggregator.class);

    /** 单个 Worker 结果在 Prompt 中的最大长度 — 避免撑爆上下文 */
    private static final int MAX_RESULT_LENGTH = 4000;

    private final LlmClient llmClient;
    private final WorkerAgentRepository workerAgentRepo;
    private final ObjectMapper objectMapper;

    private static final String AGGREGATE_PROMPT = """
        你是一个报告总编辑。多个专业团队成员已完成各自的子任务，
        你需要将他们的成果汇总为一份高质量的最终报告。

        ## 原始任务
        %s

        ## 汇总策略
        %s

        ## 各 Worker 的成果

        %s

        ## 汇总要求
        1. 去除重复信息，保留最有价值的分析和结论
        2. 发现不同 Worker 之间的矛盾信息时，标注矛盾并给出你的判断
        3. 如果有 Worker 执行失败，在报告中注明缺失的部分
        4. 输出一份结构清晰的 Markdown 报告，包含：
           - # 摘要（一段话总结核心结论）
           - # 详细分析（按主题组织，融合各 Worker 的成果）
           - # 风险与不确定性（如有）
           - # 结论与建议
        5. 报告末尾附上：
           - **整体置信度**: 1-10 分
           - **各 Worker 质量评分**: JSON 格式如 {"worker_name": 0.85, ...}，值为 0-1

        ## 质量评分格式（务必放在报告最后一行）
        ```json
        {"quality_scores": {"worker_name_1": 0.9, "worker_name_2": 0.7}}
        ```
        """;

    public ResultAggregator(LlmClient llmClient,
                            WorkerAgentRepository workerAgentRepo,
                            ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.workerAgentRepo = workerAgentRepo;
        this.objectMapper = objectMapper;
    }

    /**
     * 聚合所有 Worker 结果为最终报告。
     *
     * @param userQuery    用户原始请求
     * @param plan         任务计划（含聚合指令）
     * @param results      所有 Worker 的执行结果
     * @param callback     SSE 事件回调
     * @return 最终聚合报告（Markdown）
     */
    public String aggregate(String userQuery, TaskPlan plan,
                            List<WorkerResult> results, StreamCallback callback) {

        // 构建各 Worker 结果摘要
        StringBuilder workerResultsText = new StringBuilder();
        int successCount = 0;
        int failCount = 0;

        for (WorkerResult r : results) {
            workerResultsText.append("### Worker: ").append(r.getWorkerName());

            if (r.isSuccess()) {
                successCount++;
                workerResultsText.append(" [执行成功]\n\n");

                String output = r.getOutput();
                if (output != null && output.length() > MAX_RESULT_LENGTH) {
                    output = output.substring(0, MAX_RESULT_LENGTH)
                            + "\n\n... (已截断，原文共 " + r.getOutput().length() + " 字符)";
                }
                workerResultsText.append(output != null ? output : "(Worker 未产出内容)");
            } else {
                failCount++;
                workerResultsText.append(" [执行失败]\n\n");
                workerResultsText.append("失败原因: ").append(r.getError());
            }

            workerResultsText.append("\n\n---\n\n");
        }

        callback.onEvent("aggregating_stats", String.format(
                "汇总 %d 个 Worker 结果: %d 成功, %d 失败",
                results.size(), successCount, failCount));

        // 如果所有 Worker 都失败了，生成简化报告
        if (successCount == 0) {
            log.warn("[Aggregator] All {} workers failed, generating failure report", results.size());
            callback.onEvent("aggregating_warning", "所有 Worker 均执行失败，生成失败报告");
            return generateFailureReport(userQuery, results);
        }

        // 构建聚合 Prompt
        String aggregationInstruction = plan.getAggregationInstruction();
        if (aggregationInstruction == null || aggregationInstruction.isBlank()) {
            aggregationInstruction = "将各 Worker 的成果按逻辑顺序整合为一份完整报告";
        }

        String prompt = String.format(AGGREGATE_PROMPT,
                userQuery,
                aggregationInstruction,
                workerResultsText.toString());

        callback.onEvent("aggregating_llm", "Manager LLM 正在撰写最终报告...");
        log.info("[Aggregator] Sending aggregation prompt, length={}", prompt.length());

        String report = llmClient.chat(prompt);

        // 尝试提取并回写 Worker 质量评分
        extractAndSaveQualityScores(report, results);

        return report;
    }

    /**
     * 所有 Worker 都失败时的兜底报告。
     */
    private String generateFailureReport(String userQuery, List<WorkerResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 任务执行报告\n\n");
        sb.append("## 状态: 执行失败\n\n");
        sb.append("**原始任务**: ").append(userQuery).append("\n\n");
        sb.append("## 失败详情\n\n");
        sb.append("所有 Worker Agent 均执行失败：\n\n");

        for (WorkerResult r : results) {
            sb.append("- **").append(r.getWorkerName()).append("**: ")
                    .append(r.getError()).append("\n");
        }

        sb.append("\n## 建议\n\n");
        sb.append("1. 检查 LLM API 配置是否正确（API Key、Base URL）\n");
        sb.append("2. 检查网络连接是否正常\n");
        sb.append("3. 尝试简化任务描述后重新提交\n");
        sb.append("\n**整体置信度**: 0/10\n");

        return sb.toString();
    }

    /**
     * 从报告末尾提取 Worker 质量评分 JSON，回写到 worker_agent 表的 result_quality 字段。
     *
     * 期望格式（在报告最后）：
     * ```json
     * {"quality_scores": {"researcher": 0.9, "analyst": 0.7}}
     * ```
     */
    private void extractAndSaveQualityScores(String report, List<WorkerResult> results) {
        try {
            // 查找最后一个 ```json ... ``` 块
            int lastJsonBlock = report.lastIndexOf("```json");
            if (lastJsonBlock < 0) {
                log.debug("[Aggregator] No quality scores JSON block found in report");
                return;
            }

            int start = lastJsonBlock + 7;
            int end = report.indexOf("```", start);
            if (end <= start) return;

            String jsonStr = report.substring(start, end).trim();
            JsonNode root = objectMapper.readTree(jsonStr);
            JsonNode scores = root.path("quality_scores");

            if (scores.isMissingNode() || !scores.isObject()) {
                log.debug("[Aggregator] quality_scores field not found or not an object");
                return;
            }

            // 回写每个 Worker 的质量评分
            for (WorkerResult r : results) {
                JsonNode score = scores.path(r.getWorkerName());
                if (!score.isMissingNode() && score.isNumber()) {
                    double quality = score.asDouble();
                    workerAgentRepo.updateResultQuality(r.getWorkerName(), quality);
                    log.info("[Aggregator] Worker [{}] quality score: {}", r.getWorkerName(), quality);
                }
            }

        } catch (Exception e) {
            // 质量评分提取失败不影响主流程
            log.debug("[Aggregator] Failed to extract quality scores: {}", e.getMessage());
        }
    }
}
