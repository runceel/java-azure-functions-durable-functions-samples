package com.example.fanout;

import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.durabletask.Task;
import com.microsoft.durabletask.TaskOrchestrationContext;
import com.microsoft.durabletask.azurefunctions.DurableOrchestrationTrigger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 指定件数の Activity を10件ずつ並列実行し、結果を連結する Durable Orchestrator です。
 */
public class FanOutOrchestrator {
    private static final int BATCH_SIZE = 10;

    /**
     * バッチごとに全 Activity の完了を待ち、全件完了後に結果を結合します。
     *
     * @param context オーケストレーションの入力と Task 操作を扱うコンテキスト
     * @return 区切り文字で連結した Activity の結果
     */
    @FunctionName("FanOutOrchestration")
    public String fanOutOrchestration(
            @DurableOrchestrationTrigger(name = "ctx")
            TaskOrchestrationContext context) {
        Integer count = context.getInput(Integer.class);
        if (count == null) {
            count = 5;
        }

        List<String> results = new ArrayList<>();
        context.setCustomStatus(Map.of(
                "total", count, "completed", 0, "batchSize", BATCH_SIZE));
        for (int start = 1; start <= count; start += BATCH_SIZE) {
            List<Task<String>> tasks = new ArrayList<>();
            int end = Math.min(start + BATCH_SIZE - 1, count);
            for (int i = start; i <= end; i++) {
                tasks.add(context.callActivity(
                        "FanOutActivity", "Item " + i, String.class));
            }

            results.addAll(context.allOf(tasks).await());
            context.setCustomStatus(Map.of(
                    "total", count, "completed", results.size(), "batchSize", BATCH_SIZE));
        }

        return String.join(" | ", results);
    }
}
