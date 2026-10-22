package com.example.fanout;

import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import com.microsoft.durabletask.azurefunctions.DurableClientContext;
import com.microsoft.durabletask.azurefunctions.DurableClientInput;

public class FanOutStarter {
    @FunctionName("StartFanOut")
    public HttpResponseMessage startFanOut(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "orchestrations/fan-out")
            HttpRequestMessage<Integer> request,
            @DurableClientInput(name = "durableContext")
            DurableClientContext durableContext) {
        Integer count = request.getBody();
        if (count == null) {
            count = 5;
        }
        if (count < 1 || count > 20) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .body("The item count must be between 1 and 20.")
                    .build();
        }
        String instanceId = durableContext.getClient()
                .scheduleNewOrchestrationInstance("FanOutOrchestration", count);
        return durableContext.createCheckStatusResponse(request, instanceId);
    }
}
