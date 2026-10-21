package com.example;

import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import com.microsoft.durabletask.RetryPolicy;
import com.microsoft.durabletask.Task;
import com.microsoft.durabletask.TaskOptions;
import com.microsoft.durabletask.TaskOrchestrationContext;
import com.microsoft.durabletask.azurefunctions.DurableActivityTrigger;
import com.microsoft.durabletask.azurefunctions.DurableClientContext;
import com.microsoft.durabletask.azurefunctions.DurableClientInput;
import com.microsoft.durabletask.azurefunctions.DurableOrchestrationTrigger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class Functions {
    private static final Pattern BATCH_ID_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    @FunctionName("StartBatch")
    public HttpResponseMessage start(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.FUNCTION,
                    route = "batch/start")
            HttpRequestMessage<BatchInput> req,
            @DurableClientInput(name = "dc") DurableClientContext dc) {
        BatchInput input = req.getBody();
        if (!isValid(input)) {
            return req.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .body("Invalid batch input")
                    .build();
        }
        String instanceId = dc.getClient()
                .scheduleNewOrchestrationInstance("BatchOrchestration", input);
        return dc.createCheckStatusResponse(req, instanceId);
    }

    @FunctionName("PreparePages")
    public BatchPlan preparePages(
            @DurableActivityTrigger(name = "input") BatchInput input) {
        return new BatchService().preparePages(input);
    }

    @FunctionName("BatchOrchestration")
    public PageResult run(
            @DurableOrchestrationTrigger(name = "ctx") TaskOrchestrationContext ctx) {
        BatchInput input = ctx.getInput(BatchInput.class);
        BatchPlan plan = ctx.callActivity(
                "PreparePages", input, BatchPlan.class).await();
        return processPages(ctx, plan, input.maxParallel);
    }

    private PageResult processPages(
            TaskOrchestrationContext ctx, BatchPlan plan, int width) {
        PageResult total = new PageResult();
        for (int start = 0; start < plan.pages.length; start += width) {
            List<Task<PageResult>> tasks = startWindow(ctx, plan, start, width);
            for (PageResult result : ctx.allOf(tasks).await()) {
                total.successCount += result.successCount;
                total.failureCount += result.failureCount;
            }
        }
        return total;
    }

    private List<Task<PageResult>> startWindow(
            TaskOrchestrationContext ctx, BatchPlan plan, int start, int width) {
        TaskOptions options = new TaskOptions(
                new RetryPolicy(3, Duration.ofSeconds(5)));
        List<Task<PageResult>> tasks = new ArrayList<>();
        int end = Math.min(start + width, plan.pages.length);
        for (int i = start; i < end; i++) {
            tasks.add(ctx.callActivity(
                    "ProcessPage", plan.pages[i], options, PageResult.class));
        }
        return tasks;
    }

    @FunctionName("ProcessPage")
    public PageResult processPage(
            @DurableActivityTrigger(name = "page") PageRef page) {
        return new BatchService().importPageOnce(page);
    }

    private static boolean isValid(BatchInput input) {
        return input != null
                && input.batchId != null
                && BATCH_ID_PATTERN.matcher(input.batchId).matches()
                && input.blobName != null
                && !input.blobName.isBlank()
                && !input.blobName.startsWith("/")
                && java.util.Arrays.stream(input.blobName.split("/"))
                        .noneMatch(segment -> segment.equals(".."))
                && input.pageSize >= 1
                && input.pageSize <= 5000
                && input.maxParallel >= 1
                && input.maxParallel <= 16;
    }
}
