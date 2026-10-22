package com.example.hello;

import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.durabletask.TaskOrchestrationContext;
import com.microsoft.durabletask.azurefunctions.DurableOrchestrationTrigger;

/**
 * Hello の 3 つの Activity を順番に実行する Durable Orchestrator です。
 */
public class HelloOrchestrator {
    /**
     * 1つ前の Activity の出力を次の Activity に渡します。
     *
     * @param context オーケストレーションの入力と Activity 呼び出しを扱うコンテキスト
     * @return 最終 Activity の処理結果
     */
    @FunctionName("HelloOrchestration")
    public String helloOrchestration(
            @DurableOrchestrationTrigger(name = "ctx")
            TaskOrchestrationContext context) {
        String first = context.callActivity("StepOne", "Start", String.class).await();
        String second = context.callActivity("StepTwo", first, String.class).await();
        return context.callActivity("StepThree", second, String.class).await();
    }
}
