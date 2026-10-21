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
The plain HTTP and Blob + Queue samples are not implemented yet.

Stop a CLI-started verification session when finished:

```powershell
aspire stop --non-interactive --apphost .\apphost.cs
```
