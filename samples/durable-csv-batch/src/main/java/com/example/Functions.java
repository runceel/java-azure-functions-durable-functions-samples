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

/**
 * CSV バッチの開始、ページ分割、ページ処理を調整する Durable Functions です。
 */
public class Functions {
    private static final Pattern BATCH_ID_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    /**
     * バッチ入力を検証し、新しいオーケストレーションを開始します。
     *
     * @param req バッチ設定を本文に含む HTTP POST リクエスト
     * @param dc Durable クライアントとステータス API を生成するコンテキスト
     * @return 有効な入力ではステータスレスポンス、無効な入力では 400 レスポンス
     */
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

    /**
     * 入力 CSV を読み取り、処理用の Blob ページへ分割します。
     *
     * @param input バッチ実行パラメーター
     * @return 分割したページ参照を含む処理計画
     */
    @FunctionName("PreparePages")
    public BatchPlan preparePages(
            @DurableActivityTrigger(name = "input") BatchInput input) {
        return new BatchService().preparePages(input);
    }

    /**
     * ページ分割後、設定された並列度でページ Activity をウィンドウごとに実行します。
     *
     * @param ctx オーケストレーションコンテキスト
     * @return バッチ全体の成功件数と失敗件数
     */
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
        // ページをウィンドウに分け、ウィンドウ内だけ並列実行します。
        for (int start = 0; start < plan.pages.length; start += width) {
            List<Task<PageResult>> tasks = startWindow(ctx, plan, start, width);
            for (PageResult result : ctx.allOf(tasks).await()) {
                total.successCount += result.successCount;
                total.failureCount += result.failureCount;
            }
        }
        return total;
    }

    /**
     * 指定ウィンドウ内のページを並列 Task として登録します。
     *
     * @param ctx オーケストレーションコンテキスト
     * @param plan 分割済みページ計画
     * @param start ウィンドウの開始位置
     * @param width ウィンドウ内の最大並列数
     * @return 実行予定のページ Task
     */
    private List<Task<PageResult>> startWindow(
            TaskOrchestrationContext ctx, BatchPlan plan, int start, int width) {
        // Activity 失敗時は最大 3 回、5 秒間隔で再試行します。
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

    /**
     * 1 ページ分の CSV を PostgreSQL に冪等に登録します。
     *
     * @param page 処理対象ページの参照
     * @return 成功件数と検証失敗件数
     */
    @FunctionName("ProcessPage")
    public PageResult processPage(
            @DurableActivityTrigger(name = "page") PageRef page) {
        return new BatchService().importPageOnce(page);
    }

    /**
     * オーケストレーション開始前にバッチ入力の形式と範囲を検証します。
     *
     * @param input HTTP から受け取ったバッチ入力
     * @return 入力が有効な場合は {@code true}
     */
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
