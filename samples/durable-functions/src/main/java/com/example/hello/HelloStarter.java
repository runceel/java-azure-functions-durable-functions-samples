package com.example.hello;

import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import com.microsoft.durabletask.azurefunctions.DurableClientContext;
import com.microsoft.durabletask.azurefunctions.DurableClientInput;

public class HelloStarter {
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
        String instanceId = durableContext.getClient()
                .scheduleNewOrchestrationInstance("HelloOrchestration");
        return durableContext.createCheckStatusResponse(request, instanceId);
    }
}
