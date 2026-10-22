#:package Aspire.Hosting.Azure.Functions@13.6.1
#:package Aspire.Hosting.Azure.Storage@13.6.1
#:package Aspire.Hosting.Java@13.6.0-preview.1.26479.8
#:package Aspire.Hosting.PostgreSQL@13.6.1
#:sdk Aspire.AppHost.Sdk@13.6.1
#:property AspireUseCliBundle=true

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

var path = Environment.GetEnvironmentVariable("PATH")
    ?? throw new InvalidOperationException("The PATH environment variable is required.");

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
    .WithDataVolume();
var database = postgres.AddDatabase("batchdb", "workshop");

var httpFunctionsDirectory = System.IO.Path.GetFullPath(".\\samples\\http-check-file");
builder.AddJavaApp("http-check-file", httpFunctionsDirectory)
    .WithMavenGoal("package", "azure-functions:run")
    .WithWrapperPath(System.IO.Path.Combine(
        httpFunctionsDirectory,
        OperatingSystem.IsWindows() ? "mvnw.cmd" : "mvnw"))
    .WithHttpEndpoint(port: 7072, targetPort: 7073, name: "http")
    .WithEnvironment(
        "PATH",
        $"{httpFunctionsDirectory}{System.IO.Path.PathSeparator.ToString()}{path}")
    .WithEnvironment("AzureWebJobsStorage", "UseDevelopmentStorage=true")
    .WaitFor(storage);

var durableFunctionsDirectory = System.IO.Path.GetFullPath(".\\samples\\durable-functions");
builder.AddJavaApp("durable-functions", durableFunctionsDirectory)
    .WithMavenGoal("package", "azure-functions:run")
    .WithWrapperPath(System.IO.Path.Combine(
        durableFunctionsDirectory,
        OperatingSystem.IsWindows() ? "mvnw.cmd" : "mvnw"))
    .WithHttpEndpoint(targetPort: 7071, name: "http")
    .WithEnvironment(
        "PATH",
        $"{durableFunctionsDirectory}{System.IO.Path.PathSeparator.ToString()}{path}")
    .WithEnvironment("AzureWebJobsStorage", "UseDevelopmentStorage=true")
    .WithEnvironment(
        "DURABLE_TASK_SCHEDULER_CONNECTION_STRING",
        taskHub.Resource.ConnectionStringExpression)
    .WaitFor(storage)
    .WaitFor(scheduler);

var csvFunctionsDirectory = System.IO.Path.GetFullPath(".\\samples\\durable-csv-batch");
builder.AddJavaApp("durable-csv-batch", csvFunctionsDirectory)
    .WithMavenGoal("package", "azure-functions:run")
    .WithArgs("-DenableDebug")
    .WithWrapperPath(System.IO.Path.Combine(
        csvFunctionsDirectory,
        OperatingSystem.IsWindows() ? "mvnw.cmd" : "mvnw"))
    .WithHttpEndpoint(port: 7075, targetPort: 7074, name: "http")
    .WithEnvironment(
        "PATH",
        $"{csvFunctionsDirectory}{System.IO.Path.PathSeparator.ToString()}{path}")
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