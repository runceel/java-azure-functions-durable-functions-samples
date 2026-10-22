package com.example.hello;

import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import com.microsoft.durabletask.azurefunctions.DurableClientContext;
import com.microsoft.durabletask.azurefunctions.DurableClientInput;

/**
 * Hello の順次パターンを開始する HTTP Durable Functions の Starter です。
 */
public class HelloStarter {
    /**
     * 新しい Hello オーケストレーションを開始し、ステータス確認用のレスポンスを返します。
     *
     * @param request HTTP POST リクエスト
     * @param durableContext Durable クライアントとステータス API を生成するコンテキスト
     * @return 実行インスタンス ID を含むステータス確認レスポンス
     */
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
