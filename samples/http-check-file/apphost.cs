#:package Aspire.Hosting.Java@13.6.0-preview.1.26479.8
#:sdk Aspire.AppHost.Sdk@13.6.1
#:property AspireUseCliBundle=true

var builder = DistributedApplication.CreateBuilder(args);

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
    .WithEnvironment("OTEL_TRACES_EXPORTER", "otlp");

builder.Build().Run();
