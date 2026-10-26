$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$initializer = Join-Path (Split-Path -Parent $PSScriptRoot) 'Initialize-Database.ps1'
$originalEnvironment = @{}
foreach ($name in @('AZURE_SUBSCRIPTION_ID', 'AZURE_RESOURCE_GROUP', 'DB_INITIALIZER_NAME')) {
    $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
}
$env:AZURE_SUBSCRIPTION_ID = '00000000-0000-0000-0000-000000000000'
$env:AZURE_RESOURCE_GROUP = 'test-only'
$env:DB_INITIALIZER_NAME = 'test-only'

function az {
    $global:LASTEXITCODE = 0
    if ($args[1] -eq 'show') {
        $queryIndex = [Array]::IndexOf($args, '--query')
        if ($queryIndex -lt 0 -or $args[$queryIndex + 1] -ne 'properties.defaultHostName || defaultHostName') {
            throw 'Hostname lookup must support both ARM and flattened CLI responses.'
        }
        return 'test-only.invalid'
    }
    switch ($args[3]) {
        'set' {
            $keyIndex = [Array]::IndexOf($args, '--key-name')
            $global:initializerHookTestState.keyName = $args[$keyIndex + 1]
            $global:initializerHookTestState.creates++
            if ($global:initializerHookTestState.creates -le $global:initializerHookTestState.keyFailures) {
                $global:LASTEXITCODE = 1
                return
            }
            return
        }
        'list' {
            return @{ $global:initializerHookTestState.keyName = $global:initializerHookTestState.key } |
                ConvertTo-Json -Compress
        }
        'delete' {
            $global:initializerHookTestState.deletes++
            $global:LASTEXITCODE = $global:initializerHookTestState.deleteExitCode
            return
        }
        default { throw 'Unexpected Azure CLI command in test.' }
    }
}

function Invoke-RestMethod {
    param($Method, $Uri, $Headers, $TimeoutSec, $MaximumRedirection, [switch]$SkipHttpErrorCheck,
        $StatusCodeVariable)
    if ($Uri -ne 'https://test-only.invalid/api/database/initialize' -or
        $Method -ne 'Post' -or $Headers['x-functions-key'] -ne $global:initializerHookTestState.key -or
        $MaximumRedirection -ne 0) {
        throw 'Unexpected initializer request.'
    }
    $global:initializerHookTestState.requests++
    if ($global:initializerHookTestState.requests -le $global:initializerHookTestState.transportFailures) {
        throw [System.Threading.Tasks.TaskCanceledException]::new(
            "Simulated transport timeout with sensitive context $($global:initializerHookTestState.key)")
    }
    $index = [Math]::Min($global:initializerHookTestState.requests - 1, $global:initializerHookTestState.statuses.Count - 1)
    Set-Variable -Name $StatusCodeVariable -Value $global:initializerHookTestState.statuses[$index] -Scope 1
    return @{ initialized = $true; schemaVersion = 1 }
}

function Start-Sleep { param($Seconds) }

function Assert-True($Condition, $Message) {
    if (-not $Condition) { throw $Message }
}

function Test-Hook {
    param(
        [string] $Name,
        [int] $KeyFailures = 0,
        [int] $TransportFailures = 0,
        [int[]] $Statuses = @(200),
        [int] $DeleteExitCode = 0,
        [string] $ExpectedError = '',
        [int] $ExpectedCreates = 1,
        [int] $ExpectedRequests = 1
    )
    $global:initializerHookTestState = @{
        key = [Guid]::NewGuid().ToString('N')
        creates = 0
        deletes = 0
        requests = 0
        keyFailures = $KeyFailures
        transportFailures = $TransportFailures
        statuses = $Statuses
        deleteExitCode = $DeleteExitCode
    }
    $failure = $null
    $output = @()
    try { $output = @(& $initializer *>&1) }
    catch { $failure = $_.Exception.Message }
    if ($ExpectedError) {
        Assert-True ($null -ne $failure -and $failure.Contains($ExpectedError)) "$Name lost its original error."
    }
    else {
        Assert-True ($null -eq $failure) "$Name failed: $failure"
    }
    Assert-True ($global:initializerHookTestState.creates -eq $ExpectedCreates) "$Name made unexpected key creation calls."
    Assert-True ($global:initializerHookTestState.requests -eq $ExpectedRequests) "$Name made unexpected HTTP requests."
    Assert-True ($global:initializerHookTestState.deletes -eq 1) "$Name did not attempt key cleanup exactly once."
    Assert-True (-not (($output | Out-String) + $failure).Contains($global:initializerHookTestState.key)) "$Name exposed a key."
    Write-Host "$Name passed."
}

try {
    Test-Hook -Name 'Successful initialization'
    Test-Hook -Name 'Startup and RBAC propagation' -KeyFailures 2 -Statuses @(500, 200) `
        -ExpectedCreates 3 -ExpectedRequests 2
    Test-Hook -Name 'HTTP authorization failure' -Statuses @(403) -ExpectedError 'Initializer HTTP 403'
    Test-Hook -Name 'Transient transport timeout' -TransportFailures 1 -ExpectedRequests 2
    Test-Hook -Name 'Persistent transport timeout' -TransportFailures 10 -ExpectedRequests 10 `
        -ExpectedError 'after 10 attempts'
    Test-Hook -Name 'Key creation failure and failed cleanup' -KeyFailures 30 -DeleteExitCode 1 `
        -ExpectedCreates 30 -ExpectedRequests 0 -ExpectedError 'after 5 minutes'
    Test-Hook -Name 'Cleanup failure after successful initialization' -DeleteExitCode 1 `
        -ExpectedError 'Failed to remove temporary initializer key'
}
finally {
    foreach ($entry in $originalEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value)
    }
    $global:LASTEXITCODE = 0
    Remove-Variable -Name initializerHookTestState -Scope Global -ErrorAction SilentlyContinue
}
