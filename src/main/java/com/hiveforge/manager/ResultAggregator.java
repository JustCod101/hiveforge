package com.hiveforge.manager;

import com.hiveforge.llm.LlmClient;
import com.hiveforge.llm.StreamCallback;
import com.hiveforge.worker.WorkerResult;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ResultAggregator {

    private final LlmClient llmClient;

    private static final String AGGREGATE_PROMPT = """
        You are a report editor-in-chief. Multiple specialist team members have completed
        their sub-tasks. You need to aggregate their results into a high-quality final report.

        ## Original task
        %s

        ## Aggregation strategy
        %s

        ## Worker results
        %s

        ## Requirements
        1. Remove duplicate information, keep the most valuable analysis
        2. When contradictions are found between Workers, annotate and give your judgment
        3. Output a well-structured Markdown report
        4. End with an overall confidence score (1-10)
        """;

    public ResultAggregator(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public String aggregate(String userQuery, TaskPlan plan,
                            List<WorkerResult> results, StreamCallback callback) {

        StringBuilder workerResults = new StringBuilder();
        for (WorkerResult r : results) {
            workerResults.append("### Worker: ").append(r.getWorkerName())
                    .append(r.isSuccess() ? " [OK]" : " [FAIL]").append("\n\n");

            if (r.isSuccess()) {
                String output = r.getOutput();
                if (output.length() > 3000) {
                    output = output.substring(0, 3000) + "\n... (truncated)";
                }
                workerResults.append(output);
            } else {
                workerResults.append("Failed: ").append(r.getError());
            }
            workerResults.append("\n\n---\n\n");
        }

        String prompt = String.format(AGGREGATE_PROMPT,
                userQuery,
                plan.getAggregationInstruction(),
                workerResults.toString());

        callback.onEvent("aggregating_llm", "Manager LLM writing final report...");

        return llmClient.chat(prompt);
    }
}
