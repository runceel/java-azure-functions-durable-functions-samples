#:package Aspire.Hosting.Java@13.6.0-preview.1.26479.8
#:sdk Aspire.AppHost.Sdk@13.6.1
#:property AspireUseCliBundle=true

var builder = DistributedApplication.CreateBuilder(args);

var functionsDirectory = System.IO.Path.GetFullPath(".");
if (!System.IO.File.Exists(System.IO.Path.Combine(functionsDirectory, "pom.xml")))
{
    functionsDirectory = System.IO.Path.GetFullPath(".\\samples\\http-check-file");
}
var path = Environment.GetEnvironmentVariable("PATH")
    ?? throw new InvalidOperationException("The PATH environment variable is required.");

builder.AddJavaApp("http-check-file", functionsDirectory)
    .WithMavenGoal("package", "azure-functions:run")
    .WithWrapperPath(System.IO.Path.Combine(
        functionsDirectory,
        OperatingSystem.IsWindows() ? "mvnw.cmd" : "mvnw"))
    .WithHttpEndpoint(port: 7072, targetPort: 7071, name: "http")
    .WithEnvironment(
        "PATH",
        $"{functionsDirectory}{System.IO.Path.PathSeparator.ToString()}{path}");

builder.Build().Run();
