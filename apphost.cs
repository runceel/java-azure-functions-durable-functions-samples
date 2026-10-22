#:package Aspire.Hosting.Azure.Functions@13.6.1
#:package Aspire.Hosting.Azure.Storage@13.6.1
#:package Aspire.Hosting.Java@13.6.0-preview.1.26479.8
#:package Aspire.Hosting.PostgreSQL@13.6.1
#:package Azure.Storage.Blobs@12.29.2
#:package Azure.Storage.Queues@12.28.1
#:sdk Aspire.AppHost.Sdk@13.6.1
#:property AspireUseCliBundle=true

using Azure.Storage.Blobs;
using Azure.Storage.Queues;
using Aspire.Hosting.ApplicationModel;
using Microsoft.Extensions.DependencyInjection;

var builder = DistributedApplication.CreateBuilder(args);

#pragma warning disable ASPIREDURABLETASK001
var storage = builder.AddAzureStorage("storage")
    .RunAsEmulator(azurite =>
    {
        azurite.WithBlobPort(10000)
            .WithQueuePort(10001)
            .WithTablePort(10002);
    });
var blobs = storage.AddBlobs("blobs");
var queues = storage.AddQueues("queues");
queues.WithCommand(
    "send-message",
    "Send queue message",
    async context =>
    {
        var message = context.Arguments.GetString("message");
        if (string.IsNullOrWhiteSpace(message))
        {
            return new ExecuteCommandResult
            {
                Success = false,
                Message = "Message is required.",
            };
        }

        try
        {
            var connectionString = await ((IResourceWithConnectionString)queues.Resource)
                .GetConnectionStringAsync(context.CancellationToken);
            if (string.IsNullOrWhiteSpace(connectionString))
            {
                return new ExecuteCommandResult
                {
                    Success = false,
                    Message = "Queue connection string is unavailable.",
                };
            }

            var client = new QueueClient(
                connectionString,
                "http-check-file",
                new QueueClientOptions
                {
                    MessageEncoding = QueueMessageEncoding.None,
                });
            await client.CreateIfNotExistsAsync(cancellationToken: context.CancellationToken);
            await client.SendMessageAsync(message, cancellationToken: context.CancellationToken);

            return new ExecuteCommandResult
            {
                Success = true,
                Message = "Queue message sent.",
            };
        }
        catch (Exception exception)
        {
            return new ExecuteCommandResult
            {
                Success = false,
                Message = exception.Message,
            };
        }
    },
    new CommandOptions
    {
        IconName = "Send",
        Arguments =
        [
            new InteractionInput
            {
                Name = "message",
                Label = "Message",
                InputType = InputType.Text,
                Required = true,
                Placeholder = "Enter a message for LogQueueMessage",
                Value = "Queue trigger test message",
                MaxLength = 1024,
            },
        ],
    });
blobs.OnResourceReady(async (resource, evt, ct) =>
{
    var connectionString = await ((IResourceWithConnectionString)resource)
        .GetConnectionStringAsync(ct);
    if (string.IsNullOrWhiteSpace(connectionString))
    {
        throw new InvalidOperationException(
            "Unable to resolve the Azurite blob connection string.");
    }

    var samplePath = System.IO.Path.GetFullPath(
        ".\\samples\\durable-csv-batch\\sample-input.csv");
    if (!System.IO.File.Exists(samplePath))
    {
        throw new FileNotFoundException(
            "The CSV seed file was not found.", samplePath);
    }

    var client = new BlobServiceClient(connectionString);
    var container = client.GetBlobContainerClient("batch-inputs");
    await container.CreateIfNotExistsAsync(cancellationToken: ct);
    await container.GetBlobClient("sample-input.csv")
        .UploadAsync(samplePath, overwrite: true, cancellationToken: ct);
});

var scheduler = builder.AddDurableTaskScheduler("scheduler")
    .RunAsEmulator();

var taskHub = scheduler.AddTaskHub("default");
taskHub.OnResourceReady(async (resource, evt, ct) =>
{
    var dashboardEndpoint = scheduler.Resource.GetEndpoint("dashboard");
    if (!dashboardEndpoint.IsAllocated)
    {
        return;
    }

    var dashboardUrl = Uri.EscapeDataString(dashboardEndpoint.Url);
    var deepLink =
        $"{dashboardEndpoint.Url}/subscriptions/default/schedulers/default/taskhubs/{resource.Name}?endpoint={dashboardUrl}";
    var notifications = evt.Services.GetRequiredService<ResourceNotificationService>();

    await notifications.PublishUpdateAsync(resource, snapshot => snapshot with
    {
        Urls =
        [
            .. snapshot.Urls,
            new UrlSnapshot("auto-connect", deepLink, IsInternal: false)
            {
                DisplayProperties = new UrlDisplayPropertiesSnapshot(
                    "Task Hub Dashboard (auto-connect)",
                    0),
            },
        ],
    }).ConfigureAwait(false);
});

var workshopTaskHub = scheduler.AddTaskHub("workshop");
workshopTaskHub.OnResourceReady(async (resource, evt, ct) =>
{
    var dashboardEndpoint = scheduler.Resource.GetEndpoint("dashboard");
    if (!dashboardEndpoint.IsAllocated)
    {
        return;
    }

    var dashboardUrl = Uri.EscapeDataString(dashboardEndpoint.Url);
    var deepLink =
        $"{dashboardEndpoint.Url}/subscriptions/default/schedulers/default/taskhubs/{resource.Name}?endpoint={dashboardUrl}";
    var notifications = evt.Services.GetRequiredService<ResourceNotificationService>();

    await notifications.PublishUpdateAsync(resource, snapshot => snapshot with
    {
        Urls =
        [
            .. snapshot.Urls,
            new UrlSnapshot("auto-connect", deepLink, IsInternal: false)
            {
                DisplayProperties = new UrlDisplayPropertiesSnapshot(
                    "Task Hub Dashboard (auto-connect)",
                    0),
            },
        ],
    }).ConfigureAwait(false);
});

var postgres = builder.AddPostgres("postgres")
    .WithDataVolume()
    .WithPgAdmin();
var database = postgres.AddDatabase("batchdb", "workshop");
var inheritedPath = Environment.GetEnvironmentVariable("PATH")
    ?? throw new InvalidOperationException("The PATH environment variable is required.");

var httpFunctionsDirectory = System.IO.Path.GetFullPath(".\\samples\\http-check-file");
var httpAgentPath = System.IO.Path.GetFullPath(
    ".\\samples\\http-check-file\\target\\azure-functions\\http-check-file\\opentelemetry-javaagent.jar");
builder.AddJavaApp("http-check-file", httpFunctionsDirectory)
    .WithMavenGoal("package", "azure-functions:run")
    .WithWrapperPath(System.IO.Path.Combine(
        httpFunctionsDirectory,
        OperatingSystem.IsWindows() ? "mvnw.cmd" : "mvnw"))
    .WithHttpEndpoint(port: 7072, targetPort: 7073, name: "http")
    .WithEnvironment(
        "PATH",
        $"{httpFunctionsDirectory}{System.IO.Path.PathSeparator.ToString()}{inheritedPath}")
    .WithEnvironment("JAVA_OPTS", $"-javaagent:\"{httpAgentPath}\"")
    .WithEnvironment("OTEL_TRACES_EXPORTER", "otlp")
    .WithEnvironment("OTEL_METRICS_EXPORTER", "none")
    .WithEnvironment("OTEL_LOGS_EXPORTER", "none")
    .WithEnvironment("AzureWebJobsStorage", blobs.Resource.ConnectionStringExpression)
    .WithEnvironment("QueueStorage", queues.Resource.ConnectionStringExpression)
    .WaitFor(storage);

var durableFunctionsDirectory = System.IO.Path.GetFullPath(".\\samples\\durable-functions");
var durableAgentPath = System.IO.Path.GetFullPath(
    ".\\samples\\durable-functions\\target\\azure-functions\\durable-functions-minimal\\opentelemetry-javaagent.jar");
builder.AddJavaApp("durable-functions", durableFunctionsDirectory)
    .WithMavenGoal("package", "azure-functions:run")
    .WithWrapperPath(System.IO.Path.Combine(
        durableFunctionsDirectory,
        OperatingSystem.IsWindows() ? "mvnw.cmd" : "mvnw"))
    .WithHttpEndpoint(port: 7076, targetPort: 7071, name: "http")
    .WithEnvironment(
        "PATH",
        $"{durableFunctionsDirectory}{System.IO.Path.PathSeparator.ToString()}{inheritedPath}")
    .WithEnvironment("JAVA_OPTS", $"-javaagent:\"{durableAgentPath}\" -Dotel.instrumentation.grpc.enabled=false")
    .WithEnvironment("OTEL_TRACES_EXPORTER", "otlp")
    .WithEnvironment("OTEL_METRICS_EXPORTER", "none")
    .WithEnvironment("OTEL_LOGS_EXPORTER", "none")
    .WithEnvironment("AzureWebJobsStorage", "UseDevelopmentStorage=true")
    .WithEnvironment(
        "DURABLE_TASK_SCHEDULER_CONNECTION_STRING",
        taskHub.Resource.ConnectionStringExpression)
    .WaitFor(storage)
    .WaitFor(scheduler);

var csvFunctionsDirectory = System.IO.Path.GetFullPath(".\\samples\\durable-csv-batch");
var csvAgentPath = System.IO.Path.GetFullPath(
    ".\\samples\\durable-csv-batch\\target\\azure-functions\\durable-csv-batch\\opentelemetry-javaagent.jar");
builder.AddJavaApp("durable-csv-batch", csvFunctionsDirectory)
    .WithMavenGoal("package", "azure-functions:run")
    .WithArgs("-DenableDebug")
    .WithWrapperPath(System.IO.Path.Combine(
        csvFunctionsDirectory,
        OperatingSystem.IsWindows() ? "mvnw.cmd" : "mvnw"))
    .WithHttpEndpoint(port: 7075, targetPort: 7074, name: "http")
    .WithEnvironment(
        "PATH",
        $"{csvFunctionsDirectory}{System.IO.Path.PathSeparator.ToString()}{inheritedPath}")
    .WithEnvironment("JAVA_OPTS", $"-javaagent:\"{csvAgentPath}\" -Dotel.instrumentation.grpc.enabled=false")
    .WithEnvironment("OTEL_TRACES_EXPORTER", "otlp")
    .WithEnvironment("OTEL_METRICS_EXPORTER", "none")
    .WithEnvironment("OTEL_LOGS_EXPORTER", "none")
    .WithEnvironment("AzureWebJobsStorage", blobs.Resource.ConnectionStringExpression)
    .WithEnvironment("AZURE_STORAGE_CONNECTION_STRING", blobs.Resource.ConnectionStringExpression)
    .WithEnvironment(
        "DURABLE_TASK_SCHEDULER_CONNECTION_STRING",
        workshopTaskHub.Resource.ConnectionStringExpression)
    .WithEnvironment("DB_JDBC_URL", database.Resource.JdbcConnectionString)
    .WithEnvironment("DB_USERNAME", postgres.Resource.UserNameReference)
    .WithEnvironment("DB_PASSWORD", postgres.Resource.PasswordParameter)
    .WaitFor(storage)
    .WaitFor(scheduler)
    .WaitFor(database);

builder.Build().Run();
#pragma warning restore ASPIREDURABLETASK001