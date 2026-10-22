#:package Aspire.Hosting.Java@13.6.0-preview.1.26479.8
#:package Aspire.Hosting.Azure.Storage@13.6.1
#:package Azure.Storage.Queues@12.28.1
#:sdk Aspire.AppHost.Sdk@13.6.1
#:property AspireUseCliBundle=true

using Azure.Storage.Queues;
using Aspire.Hosting.ApplicationModel;

var builder = DistributedApplication.CreateBuilder(args);

var storage = builder.AddAzureStorage("storage")
    .RunAsEmulator(azurite =>
    {
        azurite.WithBlobPort(10000)
            .WithQueuePort(10001)
            .WithTablePort(10002);
    });
var queues = storage.AddQueues("queues");
var blobs = storage.AddBlobs("blobs");
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

var functionsDirectory = System.IO.Path.GetFullPath(".");
if (!System.IO.File.Exists(System.IO.Path.Combine(functionsDirectory, "pom.xml")))
{
    functionsDirectory = System.IO.Path.GetFullPath(".\\samples\\http-check-file");
}
var inheritedPath = Environment.GetEnvironmentVariable("PATH")
    ?? throw new InvalidOperationException("The PATH environment variable is required.");
var agentPath = System.IO.Path.GetFullPath(
    ".\\samples\\http-check-file\\target\\azure-functions\\http-check-file\\opentelemetry-javaagent.jar");
builder.AddJavaApp("http-check-file", functionsDirectory)
    .WithMavenGoal("package", "azure-functions:run")
    .WithWrapperPath(System.IO.Path.Combine(
        functionsDirectory,
        OperatingSystem.IsWindows() ? "mvnw.cmd" : "mvnw"))
    .WithHttpEndpoint(port: 7072, targetPort: 7073, name: "http")
    .WithEnvironment(
        "PATH",
        $"{functionsDirectory}{System.IO.Path.PathSeparator.ToString()}{inheritedPath}")
    .WithEnvironment("JAVA_OPTS", $"-javaagent:\"{agentPath}\"")
    .WithEnvironment("OTEL_TRACES_EXPORTER", "otlp")
    .WithEnvironment("AzureWebJobsStorage", blobs.Resource.ConnectionStringExpression)
    .WithEnvironment("QueueStorage", queues.Resource.ConnectionStringExpression)
    .WaitFor(storage);

builder.Build().Run();
