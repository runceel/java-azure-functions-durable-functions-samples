#:package Aspire.Hosting.Azure.Functions@13.6.1
#:package Aspire.Hosting.Azure.Storage@13.6.1
#:package Aspire.Hosting.Java@13.6.0-preview.1.26479.8
#:sdk Aspire.AppHost.Sdk@13.6.1
#:property AspireUseCliBundle=true

var builder = DistributedApplication.CreateBuilder(args);

#pragma warning disable ASPIREDURABLETASK001
var storage = builder.AddAzureStorage("storage")
    .RunAsEmulator(azurite =>
    {
        azurite.WithBlobPort(10000)
            .WithQueuePort(10001)
            .WithTablePort(10002);
    });

var scheduler = builder.AddDurableTaskScheduler("scheduler")
    .RunAsEmulator();

var taskHub = scheduler.AddTaskHub("default");

var functionsDirectory = System.IO.Path.GetFullPath(".\\samples\\durable-functions");
var path = Environment.GetEnvironmentVariable("PATH")
    ?? throw new InvalidOperationException("The PATH environment variable is required.");

// Aspire invokes the Maven wrapper by filename, so make the app directory searchable.
builder.AddJavaApp("durable-functions", functionsDirectory)
    .WithMavenGoal("package", "azure-functions:run")
    .WithWrapperPath(System.IO.Path.Combine(
        functionsDirectory,
        OperatingSystem.IsWindows() ? "mvnw.cmd" : "mvnw"))
    .WithHttpEndpoint(targetPort: 7071, name: "http")
    .WithEnvironment(
        "PATH",
        $"{functionsDirectory}{System.IO.Path.PathSeparator.ToString()}{path}")
    .WithEnvironment("AzureWebJobsStorage", "UseDevelopmentStorage=true")
    .WithEnvironment(
        "DURABLE_TASK_SCHEDULER_CONNECTION_STRING",
        taskHub.Resource.ConnectionStringExpression)
    .WaitFor(storage)
    .WaitFor(scheduler);

builder.Build().Run();
#pragma warning restore ASPIREDURABLETASK001