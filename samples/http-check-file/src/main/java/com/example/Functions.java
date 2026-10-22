package com.example;

import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import com.microsoft.azure.functions.annotation.QueueTrigger;

/**
 * HTTP のファイル名受付と Queue メッセージ記録を提供する Functions です。
 */
public class Functions {
    /**
     * ファイル名を検証し、受理メッセージを返します。
     *
     * @param req ファイル名を本文に含む HTTP POST リクエスト
     * @return 検証失敗時は 400、成功時は 200 を返すレスポンス
     */
    @FunctionName("CheckFile")
    public HttpResponseMessage checkFile(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.FUNCTION)
            HttpRequestMessage<String> req) {
        String blobName = req.getBody();
        // 空白だけ、または本文なしのファイル名は処理対象として受け付けません。
        if (blobName == null || blobName.isBlank()) {
            return req.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .body("blobName is required")
                    .build();
        }
        return req.createResponseBuilder(HttpStatus.OK)
                .body("Accepted file name: " + blobName)
                .build();
    }

    /**
     * Queue から受信したメッセージを実行ログへ記録します。
     *
     * @param message Queue に格納されたメッセージ
     * @param context Azure Functions の実行コンテキスト
     */
    @FunctionName("LogQueueMessage")
    public void logQueueMessage(
            @QueueTrigger(
                    name = "message",
                    queueName = "http-check-file",
                    connection = "QueueStorage")
            String message,
            com.microsoft.azure.functions.ExecutionContext context) {
        context.getLogger().info("Queue message received: " + message);
    }
}
