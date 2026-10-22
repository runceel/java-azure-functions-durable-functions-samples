package com.example.fanout;

import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.durabletask.Task;
import com.microsoft.durabletask.TaskOrchestrationContext;
import com.microsoft.durabletask.azurefunctions.DurableOrchestrationTrigger;

import java.util.ArrayList;
import java.util.List;

/**
 * 指定件数の Activity を並列実行し、結果を連結する Durable Orchestrator です。
 */
public class FanOutOrchestrator {
    /**
     * 各アイテムを Activity として起動し、全件完了後に結果を結合します。
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

        List<Task<String>> tasks = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            tasks.add(context.callActivity(
                    "FanOutActivity", "Item " + i, String.class));
        }

        List<String> results = context.allOf(tasks).await();
        return String.join(" | ", results);
    }
}
