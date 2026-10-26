[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

foreach ($name in @('AZURE_SUBSCRIPTION_ID', 'AZURE_RESOURCE_GROUP', 'DB_INITIALIZER_NAME')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "azd output or environment setting is missing: $name"
    }
}
$arguments = @('--subscription', $env:AZURE_SUBSCRIPTION_ID,
    '--resource-group', $env:AZURE_RESOURCE_GROUP, '--name', $env:DB_INITIALIZER_NAME,
    '--only-show-errors')
$hostname = & az functionapp show @arguments --query 'properties.defaultHostName || defaultHostName' --output tsv
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($hostname)) {
    throw 'Unable to resolve the initializer hostname from Azure Resource Manager.'
}
$keyName = "azd-bootstrap-$([Guid]::NewGuid().ToString('N'))"
$key = $null
$creationAttempted = $false
$initializationFailure = $null
$cleanupFailure = $null
try {
    for ($attempt = 1; $attempt -le 30; $attempt++) {
        $creationAttempted = $true
        & az functionapp function keys set @arguments --function-name InitializeDatabase `
            --key-name $keyName --output none 2>$null
        if ($LASTEXITCODE -eq 0) {
            # The create API can succeed with a null value; read the generated key separately.
            $keyJson = & az functionapp function keys list @arguments --function-name InitializeDatabase `
                --output json 2>$null
            if ($LASTEXITCODE -eq 0) {
                try {
                    $keySet = $keyJson | ConvertFrom-Json
                    $keyProperty = $keySet.PSObject.Properties[$keyName]
                    if ($null -ne $keyProperty) { $key = [string]$keyProperty.Value }
                }
                catch { throw 'Unable to read the temporary initializer function key.' }
                finally { $keyJson = $null; $keySet = $null; $keyProperty = $null }
            }
        }
        if (-not [string]::IsNullOrWhiteSpace($key)) { break }
        if ($attempt -eq 30) {
            throw 'Unable to create the temporary initializer function key after 5 minutes. Check Functions startup and RBAC.'
        }
        Write-Warning 'Initializer function keys are not ready; retrying in 10 seconds.'
        Start-Sleep -Seconds 10
    }
    for ($attempt = 1; $attempt -le 10; $attempt++) {
        try {
            $status = $null
            $result = Invoke-RestMethod -Method Post -Uri "https://$hostname/api/database/initialize" `
                -Headers @{ 'x-functions-key' = $key } -TimeoutSec 90 -MaximumRedirection 0 `
                -SkipHttpErrorCheck -StatusCodeVariable status
        }
        catch [System.Net.Http.HttpRequestException], [System.OperationCanceledException], [System.TimeoutException] {
            if ($attempt -eq 10) {
                throw 'Unable to contact the initializer after 10 attempts. No credentials or response details are logged.'
            }
            Write-Warning 'Initializer transport request failed or timed out; retrying in 15 seconds.'
            Start-Sleep -Seconds 15
            continue
        }
        catch {
            throw 'Unable to contact the initializer. No credentials or response details are logged.'
        }
        if ($status -eq 200) {
            if ($result.initialized -ne $true -or $result.schemaVersion -ne 1) {
                throw 'The initializer did not confirm schema version 1.'
            }
            break
        }
        $diagnostic = ''
        if ($result -is [string] -and $result -match '^Database initialization failed\. SQLSTATE=([0-9A-Z]{5}|unknown)$') {
            $diagnostic = " SQLSTATE=$($Matches[1])."
        }
        if ($attempt -eq 10 -or ($status -notin @(404, 409, 429) -and $status -lt 500)) {
            throw "Initializer HTTP $status.$diagnostic Check its private Application Insights logs before retrying deployment."
        }
        Write-Warning "Initializer HTTP $status.$diagnostic Waiting 15 seconds for startup and identity propagation."
        Start-Sleep -Seconds 15
    }
}
catch {
    $initializationFailure = $_
}
finally {
    $key = $null
    if ($creationAttempted) {
        & az functionapp function keys delete @arguments --function-name InitializeDatabase `
            --key-name $keyName --output none
        if ($LASTEXITCODE -ne 0) {
            $cleanupFailure = "Failed to remove temporary initializer key '$keyName'. Remove it before continuing."
        }
    }
}
if ($null -ne $initializationFailure) {
    if ($null -ne $cleanupFailure) {
        throw "$($initializationFailure.Exception.Message) $cleanupFailure"
    }
    throw $initializationFailure
}
if ($null -ne $cleanupFailure) { throw $cleanupFailure }
Write-Host 'Database initialized; runtime DML grants and sample input are ready; temporary key removed.'
