package com.example.fanout;

import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.durabletask.Task;
import com.microsoft.durabletask.TaskOrchestrationContext;
import com.microsoft.durabletask.azurefunctions.DurableOrchestrationTrigger;

import java.util.ArrayList;
import java.util.List;

public class FanOutOrchestrator {
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
