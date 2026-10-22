package com.example.hello;

import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.durabletask.TaskOrchestrationContext;
import com.microsoft.durabletask.azurefunctions.DurableOrchestrationTrigger;

public class HelloOrchestrator {
    @FunctionName("HelloOrchestration")
    public String helloOrchestration(
            @DurableOrchestrationTrigger(name = "ctx")
            TaskOrchestrationContext context) {
        String first = context.callActivity("StepOne", "Start", String.class).await();
        String second = context.callActivity("StepTwo", first, String.class).await();
        return context.callActivity("StepThree", second, String.class).await();
    }
}
