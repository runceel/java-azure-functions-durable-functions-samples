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

/**
 * fan-out/fan-in パターンを開始する HTTP Durable Functions の Starter です。
 */
public class FanOutStarter {
    /**
     * Activity の実行数を検証し、有効な場合はオーケストレーションを開始します。
     *
     * @param request 本文に Activity 数を含む HTTP POST リクエスト
     * @param durableContext Durable クライアントとステータス API を生成するコンテキスト
     * @return 正常時はステータス確認レスポンス、無効な件数時は 400 レスポンス
     */
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
        if (count < 1 || count > 100) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .body("The item count must be between 1 and 100.")
                    .build();
        }
        String instanceId = durableContext.getClient()
                .scheduleNewOrchestrationInstance("FanOutOrchestration", count);
        return durableContext.createCheckStatusResponse(request, instanceId);
    }
}
