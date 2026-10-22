package com.example;

import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import com.microsoft.azure.functions.annotation.QueueTrigger;

public class Functions {
    @FunctionName("CheckFile")
    public HttpResponseMessage checkFile(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.FUNCTION)
            HttpRequestMessage<String> req) {
        String blobName = req.getBody();
        if (blobName == null || blobName.isBlank()) {
            return req.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .body("blobName is required")
                    .build();
        }
        return req.createResponseBuilder(HttpStatus.OK)
                .body("Accepted file name: " + blobName)
                .build();
    }

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
