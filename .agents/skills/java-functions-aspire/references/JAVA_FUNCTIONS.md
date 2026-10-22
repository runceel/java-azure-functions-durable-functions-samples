# Java Azure Functions / Durable Functions を Aspire で扱う

## 対象と確認済みの範囲

Java Functions の追加・配線・起動・障害調査の共通手順です。
独立した `java-functions-aspire` Skill の詳細手順です。起動停止は `aspire-orchestration`、
観測は `aspire-monitoring` の安全規則を守って実行してください。

2026-10-21 に Windows 上で次のローカルサンプルを検証しました。

- `samples\durable-functions`: HTTP Starter → Orchestrator → Activity が DTS Emulator 上で `Completed`。
- `samples\http-check-file`: `CheckFile` が HTTP 200 と空入力時の HTTP 400 を返すことを確認。
- `samples\durable-csv-batch`: Azurite の CSV をページ分割し、DTS Emulator の Durable 処理で PostgreSQL に登録。
  サンプル入力の結果は成功4件・不正1件・ページ3件。同じ batch ID の再実行でも DB 件数が変わらないことを確認。

いずれもこの Windows 環境でのローカル検証です。全 JDK／OS での互換性は保証しません。

| 要素 | 確認時の構成 |
|---|---|
| Aspire CLI / AppHost SDK | 13.6.1 |
| `Aspire.Hosting.Azure.Functions` / `Azure.Storage` | 13.6.1 |
| `Aspire.Hosting.Java` | 13.6.0-preview.1.26479.8 |
| Maven Wrapper | Maven 3.9.11 |
| Functions Maven plugin / Java library | 1.42.0 / 3.3.0 |
| `com.microsoft:durabletask-azure-functions` | 1.9.0 |
| `azure-storage-blob` / `commons-csv` / PostgreSQL JDBC | 12.29.1 / 1.14.1 / 42.7.7 |
| コンパイルターゲット / 検証に使った JDK | Java 11 / Microsoft JDK 25.0.4.1 |
| Functions extension bundle | `[4.*, 5.0.0)` |

バージョンの正本は `apphost.cs`、`pom.xml`、wrapper properties、`host.json`。
この表は全 JDK／OS でのサポートや互換性を保証しません。更新時は実際の API と実行を再確認します。

## 1. 前提条件と作業場所

リポジトリルートを作業ディレクトリにする。現在の AppHost は
`Path.GetFullPath(".\\samples\\durable-functions")` を使うため、別 cwd からの起動は避ける。
パスには Windows の区切り文字を使い、別マシンの絶対パスをコピーしない。

必要なものは .NET SDK（file-based AppHost 用に .NET 10 以降）、
Aspire CLI、JDK、Azure Functions Core Tools v4 (`func`)、Linux コンテナを使う Docker。
Maven 本体は wrapper が取得します。ローカル実行に Azure サブスクリプション／認証／MCP は不要です。

```powershell
dotnet --list-sdks
aspire --version
func --version
java -version
Test-Path "$env:JAVA_HOME\bin\java.exe"
docker version
aspire doctor
```

`JAVA_HOME` が消えた JDK を指している場合は、インストール済みの JDK を確認し、
当該実行プロセスの環境だけを修正する。ユーザー／マシンの環境設定を無断変更しない。
Docker が停止中なら起動を確認してから進む。
既存 AppHost があるので `aspire init`、`aspire add`、再インストールを初手にしない。

## 2. 配線の不変条件

`apphost.cs` を読んでから編集する。知らない API は以下で検索し、返った ID の詳細を読む。
検索が複数語で見つからなければメソッド名だけで調べる。

```powershell
aspire docs search "Java Maven"
aspire docs api search "AddJavaApp" --language csharp
aspire docs api search "WithWrapperPath" --language csharp
aspire docs api search "WithHttpEndpoint" --language csharp
aspire docs api search "AddDurableTaskScheduler" --language csharp
```

| 設定 | 理由／禁止事項 |
|---|---|
| `AddJavaApp` + `WithMavenGoal("package", "azure-functions:run")` | Java Functions は Maven プラグイン経由で Core Tools を起動する。`run` だけでは初回の staging directory がない |
| OS に合う `mvnw.cmd` / `mvnw` を `WithWrapperPath` に渡す | グローバル Maven に依存させない |
| Functions ディレクトリを既存 PATH の先頭に追加 | 現在の preview/Windowsでは `WithWrapperPath` と併用しても `mvnw.cmd` の名前解決が失敗するため、この PATH 回避策を維持する。Maven Wrapper/FunctionsディレクトリをPATH先頭へ追加し、元のPATHを後続へ残す |
| `WithHttpEndpoint(port: 7076, targetPort: 7071, name: "http")` | 最小 Durable Functions の公開 7076 と Core Tools の内部 7071 を分離する |
| 統合 AppHost の HTTP/CSV/Durable ports | CheckFile は公開 7072 / Core Tools 7073、CSV は公開 7075 / Core Tools 7074、minimal Durable は公開 7076 / Core Tools 7071。公開 port と内部 targetPort を同値にしない |
| Azurite の接続文字列 | コードへ開発キーを埋め込まず、`storage.AddBlobs("blobs")` の `ConnectionStringExpression` を `AzureWebJobsStorage` / Java SDK 用環境変数へ渡す。`UseDevelopmentStorage=true` は Functions host のみが利用できる簡略形として扱う |
| `WaitFor(storage)` / `WaitFor(scheduler)` | 依存サービスの準備を待つ。Functions host 自体の HTTP readiness は保証しない |
| `AddDurableTaskScheduler(...).RunAsEmulator()` + `AddTaskHub("default")` | DTS 用。`ASPIREDURABLETASK001` は該当 API の範囲でのみ抑制する |
| `DURABLE_TASK_SCHEDULER_CONNECTION_STRING` に task hub の `ConnectionStringExpression` | 動的な gRPC 接続先と hub を正しく渡す。過去のポートや管理画面 URL を使わない |
| OpenTelemetry の Java 計装 | 各POMへ `azure-functions-java-opentelemetry:1.1.0`、`opentelemetry-api:1.49.0`、agent 2.11.0のpackageコピーを追加し、各 `host.json`へ `"telemetryMode": "OpenTelemetry"`を設定する。Aspire resourceへ `JAVA_OPTS=-javaagent:<staging>/opentelemetry-javaagent.jar`を渡す |
| Durable Task の gRPC計装競合 | OTel agent 2.11.0でgRPC helperのClassNotFoundが出る場合、`JAVA_OPTS`に `-Dotel.instrumentation.grpc.enabled=false`を付ける。HTTP/Functions/Durable serviceのtrace自体はこの設定でも保持され、gRPC自動計装のみ無効化する |
| Task Hub resource の `auto-connect` URL | `OnResourceReady` で `/subscriptions/default/schedulers/default/taskhubs/{hub}?endpoint={dashboardUrl}` を URL 表示へ追加し、DTS Dashboard を接続設定画面ではなく該当 hub に直接開く |

`host.json` は `durableTask.hubName=default`、
`storageProvider.type=azureManaged`、
`connectionStringName=DURABLE_TASK_SCHEDULER_CONNECTION_STRING` を保持する。
Azurite があるからといって Durable の backend を Azure Storage に戻さない。

Java の `DurableActivityTrigger`、`DurableClientInput`、`DurableOrchestrationTrigger`、
`DurableClientContext` は **`com.microsoft.durabletask.azurefunctions`**。
`com.microsoft.azure.functions.annotation` ではない。
通常の `FunctionName` / `HttpTrigger` は従来の Functions annotation package を使う。
Orchestrator に直接 I/O を入れず、Activity で実行する。

.NET 用の Functions project hosting や `AddServiceDefaults()` を Java に挿入しない。
Java hosting の OTel 環境変数があるだけで Functions 本体の分散トレースが確認済みとは言わない。

Workshop AppHost は `.http` ファイルから安定して呼べるよう、
`WithHttpEndpoint(port: 7072, targetPort: 7071, name: "http")` を使用する。
HTTP Functions host は同時に1つだけ起動すること。Core Tools のローカル起動は
通常 HTTP authorization を強制しないため、`AuthorizationLevel.FUNCTION` の関数も
ローカルの `.http` からキーなしで呼び出せる。デプロイ環境では引き続き Function key が必要。

## 3. 起動と本当の readiness

最初に `aspire ps` で既存インスタンスを確認する。
ユーザーの既存稼働を勝手に止めない。複数 AppHost がある場合は対象を明示する。
VS Code lifecycle tool がある場合は orchestration Skill の優先順位に従う。
以下は CLI fallback の手順です。

```powershell
aspire start --non-interactive --apphost .\apphost.cs
if ($LASTEXITCODE -ne 0) { throw "AppHost startup failed." }
aspire wait durable-functions --non-interactive --apphost .\apphost.cs
if ($LASTEXITCODE -ne 0) { throw "Functions resource failed to start." }
aspire describe --format Json --non-interactive --apphost .\apphost.cs
aspire logs durable-functions --non-interactive --apphost .\apphost.cs
```

**このリソースには host readiness の health check がない。**
`Healthy` の直後に Maven のダウンロードやコンパイルが続くことがある。
依存取得 → package → Core Tools → Java worker → 3 関数の発見をログで確認する。
`StartHello: [POST] .../api/orchestrations/hello` が出ても、直後の bind error を見落とさない。
必要なら `aspire logs durable-functions --follow --non-interactive --apphost .\apphost.cs`
で追跡し、ログ購読だけを停止してから検証する。購読を残さない。
初回の取得は時間がかかる。起動待ちを HTTP リトライループで置き換えない。

worktree の同時起動は `--isolated` が必要。ただしこのサンプルは内部 7071 と
Azurite のポートを固定しているため、それだけで衝突しないとは限らない。
並行インスタンスが必要なら、Core Tools のポートと対応する AppHost endpoint、
storage 接続設定を一緒に見直す。無関係なプロセスを止めて解消しない。

## 4. HTTP Starter と Durable の完了を検証

URL は必ず現在の `aspire describe` から取得する。ログの内部 7071 や過去の動的ポートをコピーしない。
以下は PowerShell での検証例。最初の受付と最終結果の両方を確認する。
status の期限付き照会は orchestration 完了確認用であり、host 起動の polling ではありません。

```powershell
$ErrorActionPreference = "Stop"
$json = aspire describe --format Json --non-interactive --apphost .\apphost.cs
if ($LASTEXITCODE -ne 0) { throw "Cannot read Aspire resource state." }
$functions = @(($json | ConvertFrom-Json).resources |
    Where-Object displayName -eq "durable-functions")
if ($functions.Count -ne 1 -or $functions[0].state -ne "Running") {
    throw "Expected exactly one running durable-functions resource."
}
$endpoints = @($functions[0].urls | Where-Object name -eq "http")
if ($endpoints.Count -ne 1) { throw "Expected exactly one HTTP endpoint." }
$endpoint = $endpoints[0].url.TrimEnd("/")
$response = Invoke-WebRequest -Method Post `
    -Uri "$endpoint/api/orchestrations/hello" -TimeoutSec 30
if ([int]$response.StatusCode -ne 202) { throw "Starter did not return HTTP 202." }
$start = $response.Content | ConvertFrom-Json
if (-not $start.id -or -not $start.statusQueryGetUri) {
    throw "Starter response is missing instance ID or status URL."
}
$deadline = [DateTime]::UtcNow.AddSeconds(60)
do {
    $status = Invoke-RestMethod -Uri $start.statusQueryGetUri -TimeoutSec 10
    if ($status.runtimeStatus -eq "Completed") { break }
    if ($status.runtimeStatus -in @("Failed", "Terminated", "Canceled")) {
        throw "Orchestration ended with $($status.runtimeStatus): $($status.output)"
    }
    if ([DateTime]::UtcNow -ge $deadline) { throw "Orchestration completion timed out." }
    Start-Sleep -Seconds 1
} while ($true)
if ($status.output -cne 'Hello, "Durable Functions"!') {
    throw "Unexpected orchestration output: $($status.output)"
}
[pscustomobject]@{
    instanceId = $start.id
    runtimeStatus = $status.runtimeStatus
    output = $status.output
}
```

現在の実測値は `Completed`、`Hello, "Durable Functions"!`。
Activity の String 入力に引用符が含まれる現行 SDK／binding の挙動が見られたため、
引用符なしの出力だったと報告しない。直す場合は I/O 契約の調査と別の検証を伴う変更として扱う。
管理 URL にはキーが含まれ得る。レスポンス全体や接続情報を永続ファイルに保存しない。

## 5. Workshop の通常 / Durable Java Functions

`samples\http-check-file` は `CheckFile` の HTTP 入力検証例です。HTTP だけなので Durable SDK、
`durableTask` provider、DTS 接続設定は不要です。`samples\durable-csv-batch` は以下を一通り示します。

- `BatchInput`、`PageRef`、`BatchPlan`、`PageResult` の JSON データ型
- `StartBatch` の入力検証と Durable 起動
- `PreparePages` による `batch-inputs` CSV のページ分割、`batch-pages` Blob への保存
- `BatchOrchestration` の並列ウィンドウ実行と Activity ごとの3回・5秒間隔のリトライ
- `ProcessPage` の PostgreSQL トランザクション、行エラー記録、ページ完了マーカーによる冪等性

CSV は `id,name` ヘッダーを持ち、`id` は正の整数、`name` は空でない値とします。
行の検証エラーは `rejected_rows` に保存して継続し、Blob／DB などのシステム障害は成功件数に
置き換えず例外として失敗させます。1つの batch ID は1つの論理入力にのみ使います。
起動・入力 Blob のアップロード・状態確認方法はルート `README.md` に記載しています。
各 HTTP 呼び出しはサンプル直下の `requests.http` に記載し、Functions host 内部ポート
7071 を Aspire の安定したローカルポート 7072 に公開しています。

## 6. 失敗の切り分けと後片付け

まず `aspire describe`、構造化ログがあれば `aspire otel logs`、
次に `aspire logs durable-functions` と必要な依存サービスのログを見る。
稼働中の実行と昔の resource のログを取り違えないよう、resource 名／起動時刻も確認する。

| 症状 | 確認・対処 |
|---|---|
| PATH簡略化後に `mvnw.cmd` が認識されない | `mvnw.cmd` を`WithWrapperPath`で指定していてもWindows/Aspire previewではFunctionsディレクトリを子プロセスのPATH先頭へ追加する回避策が必要 |
| `mvnw.cmd` が見つからず `Finished` | wrapper の存在と workDir、resource の PATH を確認。プロジェクトから `.\mvnw.cmd -v` で切り分ける |
| `Stage directory not found. Please run mvn package first.` | `package` を先に実行し、plugin の package execution があることを確認 |
| Durable の3注釈を解決できない | `com.microsoft.durabletask.azurefunctions` から import。SDK の依存 group/artifact も確認 |
| `Healthy` の後に終了 | health check がないため。Maven/Core Tools の末尾ログと exit code を確認 |
| HTTP が timeout / `address already in use` | 公開 port と `targetPort` の混同、別 host の内部 port 競合を確認。過去の失敗では Aspire proxy 自体が 7071 を占有していた |
| `JAVA_HOME` invalid | 実在する JDK を確認し当該実行の環境を修正。古いマシン固有パスを写さない |
| DTS 接続不可／いつまでも完了しない | scheduler の状態、現在の gRPC endpoint、task hub、provider、接続環境変数の名前を照合 |
| Aspire integration catalog に Java がない | 確認時は `aspire add java` が見つからなかった。現行の catalog/docs を再確認し、必要なら `apphost.cs` の検証済み `#:package` を維持する |
| `WithEnvironment` の補間で char の型エラー | Aspire の補間 overload 用に `Path.PathSeparator.ToString()` を使うか、先に通常の string を組み立てる |
| NU1902 / NU1903 警告 | 確認時は推移依存の MessagePack / Pkcs に警告。成功で隠さず、起動失敗とは分離する。更新は別途確認してから行う |

Java ソースのみの修正は resource の公開 command (`start` / `restart`) を確認して使う。
AppHost 配線を変えた場合は対象 AppHost を通常停止して再起動する。
検証のため自分で起動した AppHost は、継続利用を求められていなければ終了時に停止する。
以下も editor tool 優先の規則に従った CLI fallback です。

```powershell
aspire stop --non-interactive --apphost .\apphost.cs
if ($LASTEXITCODE -ne 0) { throw "AppHost stop failed." }
aspire ps
```

`--all` / `--force` を使わない。`target` や他人のプロセスを消して問題を隠さない。
Session 内の一時的なログ購読・検証プロセスも停止する。

## 報告と知識の更新

報告は「変更内容」「Starter HTTP status」「runtimeStatus / output」
「停止済みか」「未確認・失敗」を必要な分だけ簡潔に示す。
以前の成功を、今回変更したコードの検証結果として流用しない。
再発防止の知見はこのファイルに集約する。
ルート `AGENTS.md`、専用 Skill の入口、README のリンクを保つ。
公式 Aspire Skills は CLI 更新時に上書きされるため、ローカル知識を追記しない。
