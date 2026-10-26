[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $SubscriptionId,
    [Parameter(Mandatory)][string] $ResourceGroup
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$apps = @(& az functionapp list --subscription $SubscriptionId --resource-group $ResourceGroup `
    --output json --only-show-errors | ConvertFrom-Json)
if ($LASTEXITCODE -ne 0 -or $apps.Count -ne 4) { throw 'Expected four deployed Function Apps.' }
$keys = [System.Collections.Generic.List[object]]::new()
$verificationFailure = $null
$cleanupFailed = $false

function Get-App([string] $Service) {
    $matching = @($apps | Where-Object { $_.tags.'azd-service-name' -eq $Service })
    if ($matching.Count -ne 1) { throw "Expected one Function App for $Service." }
    $app = $matching[0]
    $hostname = & az functionapp show --subscription $SubscriptionId --resource-group $ResourceGroup `
        --name $app.name --query 'properties.defaultHostName || defaultHostName' --output tsv --only-show-errors
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($hostname)) {
        throw "Unable to resolve the hostname for $Service."
    }
    return [pscustomobject]@{ name = $app.name; defaultHostName = $hostname }
}

function New-TestKey($App, [string] $Function) {
    $entry = @{ app = $App.name; function = $Function; name = "azd-verify-$([Guid]::NewGuid().ToString('N'))" }
    $keys.Add($entry)
    & az functionapp function keys set --subscription $SubscriptionId --resource-group $ResourceGroup `
        --name $entry.app --function-name $Function --key-name $entry.name `
        --output none --only-show-errors
    if ($LASTEXITCODE -ne 0) { throw "Unable to create test key for $Function." }
    $keyJson = & az functionapp function keys list --subscription $SubscriptionId --resource-group $ResourceGroup `
        --name $entry.app --function-name $Function --output json --only-show-errors
    if ($LASTEXITCODE -ne 0) { throw "Unable to read test key for $Function." }
    try {
        $keySet = $keyJson | ConvertFrom-Json
        $property = $keySet.PSObject.Properties[$entry.name]
        if ($null -eq $property -or [string]::IsNullOrWhiteSpace([string]$property.Value)) {
            throw 'Key not returned.'
        }
        $value = [string]$property.Value
    }
    catch { throw "Unable to read test key for $Function." }
    finally { $keyJson = $null; $keySet = $null; $property = $null }
    return $value
}

function Invoke-TestRequest([string] $Uri, [string] $Method = 'Post', [string] $Body = '', [hashtable] $Headers = @{}) {
    try {
        return Invoke-WebRequest -Uri $Uri -Method $Method -Body $Body -Headers $Headers `
            -ContentType 'application/json' -TimeoutSec 90 -MaximumRedirection 0 -SkipHttpErrorCheck
    }
    catch { throw 'Verification HTTP request failed; sensitive URLs and headers are not logged.' }
}

function Wait-Orchestration($Start, [string] $Label, [switch] $IncludeHistory) {
    if ($Start.StatusCode -ne 202) { throw "$Label starter returned HTTP $($Start.StatusCode)." }
    $statusUri = ($Start.Content | ConvertFrom-Json).statusQueryGetUri
    if ($IncludeHistory) { $statusUri += '&showHistory=true' }
    $deadline = [DateTime]::UtcNow.AddMinutes(8)
    do {
        $response = Invoke-TestRequest -Uri $statusUri -Method Get
        if ($response.StatusCode -notin @(200, 202)) { throw "$Label status returned HTTP $($response.StatusCode)." }
        $status = $response.Content | ConvertFrom-Json
        switch ($status.runtimeStatus) {
            'Completed' { return $status }
            { $_ -in @('Failed', 'Terminated', 'Canceled') } { throw "$Label ended in state $($status.runtimeStatus). See private application logs." }
        }
        Start-Sleep -Seconds 5
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "$Label did not complete within eight minutes."
}

try {
    $initializer = Get-App 'db-initializer'
    $networkKey = New-TestKey $initializer 'VerifyDeployment'
    $network = Invoke-TestRequest -Uri "https://$($initializer.defaultHostName)/api/deployment/verify" `
        -Headers @{ 'x-functions-key' = $networkKey }
    if ($network.StatusCode -ne 200) { throw "Network verification returned HTTP $($network.StatusCode)." }
    $networkStatus = $network.Content | ConvertFrom-Json
    if ($networkStatus.privateSelf -ne $true -or $networkStatus.internetConnectionTimedOut -ne $true) {
        throw 'Network verification did not confirm private self-DNS and blocked public TCP egress.'
    }
    Write-Host 'Network: private self-DNS; fixed public HTTPS TCP connection timed out from the integrated VNet.'

    $http = Get-App 'http-check-file'
    $httpKey = New-TestKey $http 'CheckFile'
    $response = Invoke-TestRequest -Uri "https://$($http.defaultHostName)/api/CheckFile" `
        -Body '"sample-input.csv"' -Headers @{ 'x-functions-key' = $httpKey }
    if ($response.StatusCode -ne 200 -or $response.Content -notmatch 'Accepted file name:') {
        throw "CheckFile returned unexpected HTTP $($response.StatusCode) or body."
    }
    $unauthenticated = Invoke-TestRequest -Uri "https://$($http.defaultHostName)/api/CheckFile" -Body '"sample-input.csv"'
    if ($unauthenticated.StatusCode -ne 401) { throw 'CheckFile did not enforce function authorization.' }
    Write-Host 'CheckFile: HTTP 200 with key, HTTP 401 without key.'

    $durable = Get-App 'durable-functions'
    $sequential = Wait-Orchestration (Invoke-TestRequest `
        -Uri "https://$($durable.defaultHostName)/api/orchestrations/hello") 'Sequential'
    $expectedSequential = 'Start'
    foreach ($step in 1..3) {
        $expectedSequential = (ConvertTo-Json -InputObject $expectedSequential -Compress) + " -> step $step"
    }
    if ($sequential.output -ne $expectedSequential) {
        throw 'Sequential completed with an unexpected output.'
    }
    Write-Host 'Sequential: Completed with the exact three-step output.'

    $fanOut = Wait-Orchestration (Invoke-TestRequest `
        -Uri "https://$($durable.defaultHostName)/api/orchestrations/fan-out" -Body '12') 'Fan-out'
    $expected = (1..12 | ForEach-Object { "Processed `"Item $_`"" }) -join ' | '
    if ($fanOut.output -ne $expected -or $fanOut.customStatus.completed -ne 12) {
        throw 'Fan-out completed with incorrect results or progress.'
    }
    Write-Host 'Fan-out: Completed 12 activities across two windows in input order.'

    $csv = Get-App 'durable-csv-batch'
    $csvKey = New-TestKey $csv 'StartBatch'
    $inputBody = @{ batchId = "azure-verify-$([Guid]::NewGuid().ToString('N'))"
        blobName = 'sample-input.csv'; pageSize = 200; maxParallel = 2 } | ConvertTo-Json -Compress
    foreach ($run in 1..2) {
        $batch = Wait-Orchestration (Invoke-TestRequest -Uri "https://$($csv.defaultHostName)/api/batch/start" `
            -Body $inputBody -Headers @{ 'x-functions-key' = $csvKey }) "CSV run $run" -IncludeHistory
        if ($batch.output.successCount -ne 490 -or $batch.output.failureCount -ne 10) {
            throw "CSV run $run completed with incorrect counts."
        }
        $pages = @($batch.historyEvents | Where-Object {
            $_.EventType -eq 'TaskCompleted' -and $_.FunctionName -eq 'ProcessPage'
        })
        if ($pages.Count -ne 3) { throw "CSV run $run did not process exactly three pages." }
    }
    Write-Host 'CSV: Completed twice with the same batch ID; pages=3, success=490, rejected=10 on each run.'
}
catch {
    $verificationFailure = $_
}
finally {
    $networkKey = $null
    $httpKey = $null
    $csvKey = $null
    foreach ($entry in $keys) {
        & az functionapp function keys delete --subscription $SubscriptionId --resource-group $ResourceGroup `
            --name $entry.app --function-name $entry.function --key-name $entry.name --output none --only-show-errors
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Remove temporary key '$($entry.name)' from $($entry.app)/$($entry.function)."
            $cleanupFailed = $true
        }
    }
}
if ($null -ne $verificationFailure) {
    if ($cleanupFailed) { throw "$($verificationFailure.Exception.Message) Verification key cleanup also failed." }
    throw $verificationFailure
}
if ($cleanupFailed) { throw 'Verification key cleanup failed. Remove the reported keys before continuing.' }
Write-Host 'Application verification passed; temporary verification keys removed.'
