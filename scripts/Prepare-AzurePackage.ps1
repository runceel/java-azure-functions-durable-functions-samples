[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $ProjectDirectory,
    [Parameter(Mandatory)][string] $AppName
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$project = (Resolve-Path -LiteralPath $ProjectDirectory).Path
$stage = Join-Path $project "target\azure-functions\$AppName"
$hostPath = Join-Path $stage 'host.json'
if (-not (Test-Path -LiteralPath $hostPath)) {
    throw "Maven must package the Functions app before Azure preparation: $AppName"
}
$hostConfig = Get-Content -Raw -LiteralPath $hostPath | ConvertFrom-Json -AsHashtable
[void]$hostConfig.Remove('extensionBundle')
[void]$hostConfig.Remove('telemetryMode')
$hostConfig | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $hostPath -Encoding utf8NoBOM

# Cloud uses the Application Insights agent, not the local Functions OTel invocation middleware.
foreach ($dependency in Get-ChildItem -LiteralPath (Join-Path $stage 'lib') `
        -Filter 'azure-functions-java-opentelemetry-*.jar' -File) {
    Remove-Item -LiteralPath $dependency.FullName
}
$localAgent = Join-Path $stage 'opentelemetry-javaagent.jar'
if (Test-Path -LiteralPath $localAgent) {
    Remove-Item -LiteralPath $localAgent
}

$bindingSet = switch ($AppName) {
    'http-check-file' { 'queues' }
    'durable-functions-minimal' { 'durable' }
    'durable-csv-batch' { 'durable' }
    'db-initializer' { 'none' }
    default { throw "Unknown Functions package: $AppName" }
}
if ($bindingSet -ne 'none') {
    $repository = Split-Path -Parent $PSScriptRoot
    $extensionProject = Join-Path $repository 'infra\extensions\extensions.csproj'
    $intermediate = Join-Path $project 'target\azure-host-extensions\obj\'
    $buildOutput = Join-Path $project 'target\azure-host-extensions\build\'
    $output = Join-Path $project 'target\azure-host-extensions\publish'
    foreach ($directory in @($intermediate, $buildOutput, $output)) {
        if (Test-Path -LiteralPath $directory) {
            Remove-Item -LiteralPath $directory -Recurse -Force
        }
    }
    & dotnet build $extensionProject --configuration Release --runtime linux-x64 `
        --output $output "-p:BindingSet=$bindingSet" "-p:BaseIntermediateOutputPath=$intermediate" `
        "-p:BaseOutputPath=$buildOutput" --nologo --verbosity quiet
    if ($LASTEXITCODE -ne 0) { throw "Binding extension compilation failed for $AppName." }
    $bindingDirectory = $output
    $metadata = Join-Path $bindingDirectory 'extensions.json'
    if (-not (Test-Path -LiteralPath $metadata)) {
        throw "Binding extension metadata was not generated for $AppName."
    }
    $extensions = Get-Content -Raw -LiteralPath $metadata
    $expected = if ($bindingSet -eq 'durable') { 'AzureManaged' } else { 'Queues' }
    if ($extensions -notmatch $expected) {
        throw "Required $expected binding registration is missing from $AppName."
    }
    Copy-Item -LiteralPath $bindingDirectory -Destination (Join-Path $stage 'bin') -Recurse -Force
}
$agent = Join-Path $stage 'applicationinsights-agent.jar'
if (-not (Test-Path -LiteralPath $agent)) {
    throw "The Azure Monitor Java agent is missing from $AppName."
}
$localSettings = Join-Path $stage 'local.settings.json'
if (Test-Path -LiteralPath $localSettings) {
    Remove-Item -LiteralPath $localSettings
}
Write-Host "Prepared ${AppName}: local Linux bindings, no extension bundle or remote build."
