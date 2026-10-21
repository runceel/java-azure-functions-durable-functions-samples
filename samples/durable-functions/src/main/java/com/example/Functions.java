package com.example;

import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import com.microsoft.durabletask.DurableTaskClient;
import com.microsoft.durabletask.TaskOrchestrationContext;
import com.microsoft.durabletask.azurefunctions.DurableActivityTrigger;
import com.microsoft.durabletask.azurefunctions.DurableClientInput;
import com.microsoft.durabletask.azurefunctions.DurableClientContext;
import com.microsoft.durabletask.azurefunctions.DurableOrchestrationTrigger;

public class Functions {
    @FunctionName("StartHello")
    public HttpResponseMessage startHello(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "orchestrations/hello")
            HttpRequestMessage<Void> request,
            @DurableClientInput(name = "durableContext")
            DurableClientContext durableContext) {
        DurableTaskClient client = durableContext.getClient();
        String instanceId = client.scheduleNewOrchestrationInstance("HelloOrchestration");
        return durableContext.createCheckStatusResponse(request, instanceId);
    }

    @FunctionName("HelloOrchestration")
    public String helloOrchestration(
            @DurableOrchestrationTrigger(name = "ctx")
            TaskOrchestrationContext context) {
        return context.callActivity("SayHello", "Durable Functions", String.class).await();
    }

    @FunctionName("SayHello")
    public String sayHello(
            @DurableActivityTrigger(name = "name") String name) {
        return "Hello, " + name + "!";
    }
}
