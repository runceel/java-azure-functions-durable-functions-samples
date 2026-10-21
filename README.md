# java-azure-functions-durable-functions-samples

## AI Agent guidance

Start with [AGENTS.md](AGENTS.md), then read the repository-owned
[java-functions-aspire skill](.agents/skills/java-functions-aspire/SKILL.md).
Its [runbook](.agents/skills/java-functions-aspire/references/JAVA_FUNCTIONS.md)
covers prerequisites, Java/Maven hosting, ordinary Functions additions, DTS wiring,
readiness, end-to-end verification, and troubleshooting. Official Aspire skills are
kept unchanged because CLI updates may overwrite them.

## Minimal Durable Functions sample

This sample starts a Java Azure Functions host, Azurite, and the Durable Task Scheduler
emulator through Aspire.

### Prerequisites

- .NET 10 SDK and Aspire CLI
- JDK 11 or later, with `JAVA_HOME` set to the installed JDK
- Azure Functions Core Tools v4 (`func` on PATH)
- Docker Desktop running with the Linux container engine

### Run

From the repository root:

```powershell
aspire start --non-interactive --apphost .\apphost.cs
aspire wait durable-functions --non-interactive --apphost .\apphost.cs
aspire logs durable-functions --non-interactive --apphost .\apphost.cs
```

`Healthy` can be reported while Maven is still building. Confirm that the Functions
host has discovered `StartHello`, `HelloOrchestration`, and `SayHello`, and has not
logged a startup failure. For foreground use, run `aspire run --apphost .\apphost.cs`.

Start an orchestration:

```powershell
$functions = (aspire describe --format Json --apphost .\apphost.cs | ConvertFrom-Json).resources |
    Where-Object displayName -eq "durable-functions"
$endpoint = $functions.urls |
    Where-Object name -eq "http" |
    Select-Object -ExpandProperty url
$start = Invoke-RestMethod -Method Post -Uri "$endpoint/api/orchestrations/hello"
$start
```

Use the returned `statusQueryGetUri` to inspect the instance. When `runtimeStatus` is
`Completed`, `output` is `Hello, "Durable Functions"!`. The Aspire dashboard also exposes
the Durable Task Scheduler emulator dashboard.

The quoted name is the observed output of the current SDK/binding combination.
See the runbook for a bounded completion check that verifies the exact output.

## Workshop samples

The additional samples used by the Durable Functions Java workshop are independent of
the minimal sample above. Run commands from the repository root. They use local
emulators and do not require Azure sign-in or cloud resources.

### HTTP `CheckFile`

Build and run the standalone HTTP example:

```powershell
.\samples\http-check-file\mvnw.cmd -B -ntp package
aspire start --non-interactive --apphost .\samples\http-check-file\apphost.cs
aspire wait http-check-file --non-interactive --apphost .\samples\http-check-file\apphost.cs
```

For runnable requests, open `samples\http-check-file\requests.http` in
VS Code with the REST Client extension (or another editor that supports `.http`
request files) and run either request. Aspire maps the Functions host's internal
port 7071 to the stable local endpoint `http://localhost:7072`, which the request
file uses. Core Tools does not enforce HTTP authorization locally by default,
even when a function uses `AuthorizationLevel.FUNCTION`. Deployed functions still
require a function key. To use another HTTP client, get the current Aspire endpoint
from `aspire describe --format Json --apphost .\samples\http-check-file\apphost.cs`.

### Durable CSV batch

The batch sample splits a CSV from the `batch-inputs` Blob container into pages in
`batch-pages`, imports valid rows to PostgreSQL, and records invalid rows separately.
The PostgreSQL data volume persists across AppHost restarts. Use a new `batchId` for
each logical run; completed page markers make Activity retries idempotent for that batch.

```powershell
.\samples\durable-csv-batch\mvnw.cmd -B -ntp package
aspire start --non-interactive --apphost .\samples\durable-csv-batch\apphost.cs
aspire wait durable-csv-batch --non-interactive --apphost .\samples\durable-csv-batch\apphost.cs
```

#### Debug with VS Code

The batch AppHost starts the Functions host with Java debugging enabled on port
`5005`. Open this repository in VS Code with the Java Extension Pack installed,
set a breakpoint in `samples\durable-csv-batch\src\main\java`, and start the
`Attach to Durable CSV Functions` configuration from **Run and Debug** (F5).
Then send a request from `samples\durable-csv-batch\requests.http` to hit the
breakpoint. The debug listener is for local development only.

Create the input container and upload the included CSV to the local Azurite instance.
The Azurite development account/key and ports below are local emulator defaults only:

```powershell
$storage = 'DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://127.0.0.1:10000/devstoreaccount1;QueueEndpoint=http://127.0.0.1:10001/devstoreaccount1;TableEndpoint=http://127.0.0.1:10002/devstoreaccount1;'
az storage container create --name batch-inputs --connection-string $storage
az storage blob upload --container-name batch-inputs --name sample-input.csv --file .\samples\durable-csv-batch\sample-input.csv --connection-string $storage --overwrite
```

Upload the input as above, then open
`samples\durable-csv-batch\requests.http`. Run **Start the CSV batch**, then run
**Check the orchestration status** repeatedly until its response reports
`runtimeStatus` as `Completed`. Change `@batchId` when starting a new logical
input; reusing an ID intentionally exercises the idempotent replay behavior.
The request file calls the stable Aspire endpoint on port 7072. Run only one
workshop AppHost at a time because both Java Functions hosts use internal port 7071.

The start request body is:

```json
{
  "batchId": "workshop-001",
  "blobName": "sample-input.csv",
  "pageSize": 2,
  "maxParallel": 2
}
```

The status request reads `statusQueryGetUri` from the preceding start response. The
included input should finish with four successful rows and one rejected row. PostgreSQL tables
`imported_records`, `rejected_rows`, and `batch_page_runs` are created on first import.
Malformed CSV/header or infrastructure errors fail the orchestration; row validation
errors are recorded and do not stop the other rows.

Stop only the AppHost you started:

```powershell
aspire stop --non-interactive --apphost .\samples\http-check-file\apphost.cs
# Or, for the batch sample:
aspire stop --non-interactive --apphost .\samples\durable-csv-batch\apphost.cs
```

The SQL row registration and page completion marker are committed in one PostgreSQL
transaction. The orchestrator limits concurrency in windows and applies a three-attempt,
five-second retry policy to each page activity.

Stop a CLI-started verification session when finished:

```powershell
aspire stop --non-interactive --apphost .\apphost.cs
```
