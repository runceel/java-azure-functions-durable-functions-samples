# java-azure-functions-durable-functions-samples

### 前提条件

- .NET 10 SDK と Aspire CLI
- インストール済み JDK を指す `JAVA_HOME` を設定した JDK 11 以降
- Azure Functions Core Tools v4（`func` を PATH に設定）
- Linux コンテナーエンジンを使用する Docker Desktop

### 実行

リポジトリルートから次を実行します。

```powershell
aspire start --non-interactive --apphost .\apphost.cs
aspire wait http-check-file --non-interactive --apphost .\apphost.cs
aspire wait durable-functions --non-interactive --apphost .\apphost.cs
aspire wait durable-csv-batch --non-interactive --apphost .\apphost.cs
```

フォアグラウンドで実行する場合は、次を使用します。

```powershell
aspire run --apphost .\apphost.cs
```

## サンプルの動かし方（ローカル）

コマンドはリポジトリルートから実行してください。ローカル Emulator を使用するため、
Azure へのサインインやクラウドリソースは不要です。

`samples\http-check-file` は HTTP 入力検証と Queue trigger の例です。
`samples\durable-csv-batch` は Azurite の CSV を Durable Activity で処理し、
PostgreSQL に登録する例です。両方ともルートの AppHost から起動します。

### 最小構成の Durable Functions

このサンプルはルートの `apphost.cs` から Aspire 経由で起動します。
順次パターンと fan-out/fan-in パターンを含み、DTS Emulator の task hub を使用します。

```powershell
aspire start --non-interactive --apphost .\apphost.cs
aspire wait durable-functions --non-interactive --apphost .\apphost.cs
```

公開エンドポイントは `http://localhost:7076` です。
`samples\durable-functions\requests.http` で Sequential または Fan-out を実行します。
開始レスポンスに含まれる `statusQueryGetUri` でステータスを確認し、
`runtimeStatus` が `Completed` になるまで待ちます。

Sequential パターンでは `StepOne`、`StepTwo`、`StepThree` を順番に実行します。
成功時の出力は現在の実装では `"\"Start\" -> step 1" -> step 2" -> step 3` です。
Fan-out/fan-in パターンは、本文の項目数（1～100、省略時は5）分の Activity を、
最大10件ずつ並列実行します。各バッチが全件完了してから次のバッチへ進み、
`Processed "Item 1" | Processed "Item 2" | ...` の形式で入力順に結果を結合します。
Activity は1件あたり3～6秒弱の処理を模擬します。`100` なら10バッチになり、
全体で少なくとも約30秒かかります（実際の時間はワーカーの並列度などに依存します）。

#### 中断・再開のデモ

変更前のコードで開始済みのインスタンスは使わず、変更後のコードで新しく開始してください。
Orchestrator の変更を実行途中のインスタンスに適用すると、履歴のリプレイと整合しない場合があります。

1. `requests.http` の Fan-out リクエスト（本文 `100`）を実行します。
2. ステータスを照会し、`runtimeStatus: Running` と
   `customStatus: {"total":100,"completed":10,"batchSize":10}` などを確認します。
   `completed` はバッチ単位で確定した完了件数です。
3. Aspire Dashboard で **durable-functions だけ**を Stop → Start します。
   CLI から操作する場合は、以下のコマンドを使用します。
4. Functions host の起動後、**元の `statusQueryGetUri`** を再度照会します。
   Starter を再実行せず、同じインスタンスが `Completed`、
   `customStatus.completed: 100` となり、Item 1～100 の結果を返すことを確認します。

```powershell
aspire resource durable-functions stop --non-interactive --apphost .\apphost.cs
aspire resource durable-functions start --non-interactive --apphost .\apphost.cs
aspire wait durable-functions --non-interactive --apphost .\apphost.cs
```

`aspire wait` の成功後も、ログで Functions host の起動完了を確認してください。
**scheduler、storage、AppHost 全体は停止しないでください。** このデモは Functions
プロセスの再起動からの復旧であり、DTS Emulator 自体の再起動後のデータ保持は対象外です。
再起動時には Orchestrator が履歴をリプレイし、完了済み Activity の結果を再利用します。
停止時に実行中だった Activity は再実行される場合があります（exactly-once の保証ではありません）。

検証が完了したら、CLI で起動したセッションを停止します。

```powershell
aspire stop --non-interactive --apphost .\apphost.cs
```

## Azure へのデプロイ（Bicep + azd）

ローカルでは Aspire で各サービスを起動します。
Azure へのデプロイには `azure.yaml` と `infra\main.bicep` を使用します。
DTS の public 接続と Azure Monitor 系の public query 設定もこの Bicep に含み、
`azd up` でアプリとまとめてデプロイします。
実環境での確認結果と未検証事項は、後半の「Azure 上で確認する項目と制限」を参照してください。

| 対象 | Azure 用の設定 |
|---|---|
| Function Apps | Java 11 / Linux Flex Consumption。既存3アプリと独立した `db-initializer` |
| HTTP / SCM | public HTTPS。TLS 1.2 以上、FTP 無効、SCM/FTP の基本認証無効 |
| Storage | アプリごとの host/deployment Storage と業務用 Storage。Private Endpoint、public network と Shared Key を無効化 |
| Durable Task Scheduler | Consumption、2 task hubs、Functions は Private Endpoint 経由。ダッシュボードとデータ API は public 接続を許可、Entra 認証・RBAC 必須 |
| PostgreSQL | Private Endpoint、public network 無効、Entra 認証のみ |
| Application Insights / Logs | AMPLS 経由の private ingestion。クエリのみ public を許可、Entra 認証・RBAC 必須、ローカル認証無効 |
| Functions outbound | VNet integration。Private Endpoint subnet の TCP 443/5432 とプラットフォーム DNS だけを許可し、残りの新規 outbound を NSG で拒否 |

Flex Consumption は、VNet integration を設定するとアプリケーションの outbound 通信を
VNet にルーティングします。NSG では、Private Endpoint subnet の TCP 443/5432 を
許可し、それ以外の新規 outbound 通信を拒否します。許可条件は宛先 subnet と TCP ポートです。
Azure 提供 DNS はプラットフォーム標準の経路を使用します。
public HTTP への応答は、受信時に確立された接続を通じて返します。
この制限の対象はアプリケーションの VNet 通信です。Azure 管理プレーンや
プラットフォーム内部の通信については、各サービスの通信仕様を別途確認する必要があります。

各 Function App 自身にも Private Endpoint と `privatelink.azurewebsites.net` の
private DNS を用意します。Functions host のトリガー同期は自身のホスト名の
`/operations/settriggers` を呼ぶため、VNet 内では同じホスト名を private IP に解決します。
public network access は明示的に有効のままなので、外部の HTTP / SCM アクセスは維持します。
自分自身への呼び出しも Private Endpoint を通るため、outbound の許可範囲を維持できます。

### Durable Task Scheduler ダッシュボード

Azure Portal で scheduler の task hub（`default` または `workshop`）を開き、
ダッシュボードのリンクからオーケストレーションの状態や履歴を確認できます。
このリンクは task hub の ARM プロパティ `dashboardUrl` として取得できます。
URL には subscription / scheduler / task hub のパスと `endpoint` / `tenantId` が含まれ、
対象の task hub と Entra 認証先が設定されます。
サンプルを手元の PC から操作できるように、DTS の public 接続を有効にしています。
この設定はダッシュボードとデータ API に共通で、どちらも Entra 認証と RBAC が必要です。
デプロイ利用者には scheduler に限定した `Durable Task Data Contributor` を付与します。
このロールには閲覧に加え、オーケストレーションの作成・停止・削除などの操作権限も含まれます。
azd は利用者の Object ID を `AZURE_PRINCIPAL_ID` から Bicep に渡します。

`Task hub not found` と表示される場合は、ダッシュボードに保存された接続設定も確認してください。
`/?endpoint=...&taskhub=...` の形式では、現在の画面に認証なしの接続が保存されます。
その設定は正式な URL を開き直しても残るため、Home で対象の接続カードの
`Delete endpoint` → `Remove Entire Endpoint` を選択してから、Portal のリンクを開き直します。
削除するのはブラウザーの保存済み接続だけで、Azure の scheduler や task hub は保持されます。

Functions は VNet 内の private DNS で DTS のホスト名を private IP に解決し、
引き続き Private Endpoint を通じて接続します。Internet 向け outbound の拒否も維持します。

### 認証と DB 初期化

各 Function App に別々の User Assigned Managed Identity を割り当てます。
host/deployment Blob、Queue、業務 Blob、DTS、PostgreSQL、テレメトリの認証は MI です。
DB 初期化用 MI だけを PostgreSQL Entra administrator とします。
DB 初期化用アプリがスキーマを作成し、業務用 MI に必要な権限を付与します。業務用 MI には
`batch_runtime` ロール経由で CONNECT / schema USAGE / 対象3テーブルの
SELECT・INSERT・UPDATE のみを付与し、業務用アプリは初期化済みのテーブルを使用します。
JDBC は `verify-full` と `DefaultJavaSSLFactory` を組み合わせ、JDK の trust store で
証明書チェーンとホスト名を検証します。

`azd` の `postdeploy` hook が、別アプリの
`POST /api/database/initialize` を呼び出します。入力パラメーターや任意 SQL は受け付けません。
同じ初期化は再実行でき、既存ロールの Object ID 不一致では停止します。
Entra ロールの作成・検証は、管理関数が存在する `postgres` DB で先にコミットします。
その後の `workshop` DB の DDL と権限設定も別のトランザクション／advisory lock で保護し、
途中失敗からの再実行に対応します。
サンプル CSV の Blob が存在する場合は、その内容を保持します。
存在しない場合にサンプル CSV を作成します。
DB コミット後に Blob 準備が失敗した場合も、hook を再実行できます。

このサンプルの例外として、初期化 HTTP の呼び出しには Function key を使用します。
hook はデプロイ主体の Azure 管理権限で一時的な function-scoped key を作成し、
メモリ内からヘッダーで送信した後、`finally` で削除します。キーをソース、azd output、
URL、ログへ保存しません。作成・削除に失敗した場合は hook も失敗します。
作成 API がキーの値を返さない場合にも対応するため、作成後に対象キーを別途読み取ります。
SQL 失敗時はキー認証済み応答に SQLSTATE だけを返し、SQL 本文や資格情報は返しません。
プロセスを強制終了した場合は `azd-bootstrap-` で始まる残存キーを手動で削除してください。
Function key は Entra 認証の代替となる本番向けの利用者認証ではありません。

### 前提条件とコマンド

ローカル用の前提条件に加え、Azure CLI、Azure Developer CLI、PowerShell 7、
.NET 8 をターゲットにした拡張機能を build できる .NET SDK が必要です。
ビルド端末は Maven Central / NuGet に到達できる必要があります。
デプロイ主体には RG 作成、リソース作成、RBAC 付与、Functions key 管理の権限が必要です。
必要な resource providers は `Microsoft.Web`、`Microsoft.App`、`Microsoft.Storage`、
`Microsoft.Network`、`Microsoft.DurableTask`、`Microsoft.DBforPostgreSQL`、
`Microsoft.Insights`、`Microsoft.OperationalInsights`、`Microsoft.ManagedIdentity` です。
Java Flex、DTS Private Link、PostgreSQL が利用可能なリージョンと quota を確認してください。

リポジトリルートで実行します。subscription と location は利用する環境を明示的に選択します。

```powershell
az login
azd auth login
azd env new private-sample
azd env set AZURE_SUBSCRIPTION_ID "<subscription-id>"
azd env set AZURE_LOCATION "<supported-region>"
azd provision --preview
azd up
```

初回は `azd up` を使用します。4アプリを package/deploy した後に、
`postdeploy` hook が DB を初期化します。初回起動と MI/RBAC 反映を
待つ bounded retry を含み、一時的な接続失敗・timeout も最大10回の HTTP 試行内で再試行します。
再試行中も、依存サービスの閉域設定と Shared Key 無効の設定を維持します。
初期化のみの再実行は、対象環境を選択した上で
`azd hooks run postdeploy` を使用します。

### Azure 実行用成果物の作成

Maven の `azure` profile は、azd が渡す `AZURE_ENV_NAME` がある場合に有効になります。
次のコマンドで、Azure 用の package とデプロイテンプレートをビルド端末上に生成します。

```powershell
.\samples\durable-csv-batch\mvnw.cmd -f .\pom.xml -Pazure package
az bicep build --file .\infra\main.bicep --outfile .\infra\main.json
```

通常の Java unit tests は `.\samples\durable-csv-batch\mvnw.cmd -f .\pom.xml test`、
初期化 hook の retry／失敗／キー削除テストは
`pwsh -NoProfile -File .\scripts\tests\Initialize-Database.Tests.ps1` で実行できます。
hook テストは、mock の Azure CLI と HTTP 応答を使ってビルド端末上で実行します。

ローカル実行では extension bundle と OTel agent を使用します。
Azure 用の成果物には、Linux 向けの .NET binding extensions と
Application Insights Java agent を同梱します。
Functions の staging 完了後に拡張機能を build し、`azure` profile が staging 内の
設定と依存ファイルを Azure 用に整えます。staging の `host.json` から
`extensionBundle` を除去し、ソースの `host.json` はローカル用の設定を保持します。
再 package 時には Functions staging を消去し、クラウド用 DLL の残存を防ぎます。
Azure 成果物からはローカル用 OTel agent と
`azure-functions-java-opentelemetry` invocation middleware を除去します。
このライブラリの 1.1.0 は例外時に middleware chain を再実行するため、
Durable の yield を通常終了に変えてしまう組み合わせがあります。ローカル用の依存関係は維持します。
remote build は無効です。

Azure の Java 11 と API の互換性を保つため Maven は `release=11` を使用します。
新しい JDK で `source` / `target` だけを指定すると、Java 11 にない API の呼び出しが
コンパイルを通過し、クラウドで `NoSuchMethodError` になることがあります。

azd は複数アプリを並列に package するため、拡張機能の intermediate / build / output は
各アプリの `target` 配下に分離します。
Java 用の拡張機能ビルドには、通常の .NET SDK と ExtensionsMetadataGenerator を使います。
`AzurePlatformDNS` service tag は Deny 専用なので Allow ルールを作らず、
Azure 提供 DNS の標準の非フィルタ動作を維持しています。

これは Microsoft が説明する
[explicit extension installation](https://learn.microsoft.com/azure/azure-functions/functions-bindings-register#explicitly-install-extensions)
を使用します。ただし公式の第一推奨は extension bundle であり、
**Java + DTS + Flex + Internet 全拒否の組み合わせを公式が動作保証した構成という意味ではありません。**

### Azure 上で確認する項目と制限

Japan East の実環境で `azd up` の provision / 4アプリの deploy / 初期化 hook が成功しました。
4アプリの再起動後も、HTTP のキー認証、順次 Durable の3段の期待出力、
12件の fan-out/fan-in、CSV の成功490件・不正10件・ページ3件（pageSize=200）と
同じ batch ID での再実行を確認しました。DB 初期化 hook も再実行可能です。
13個の Private Endpoint は Approved、Storage / PostgreSQL の public network と
Storage の Shared Key は無効、Functions の VNet 統合と resource-scoped MI ロールを確認しました。
Internet 拒否の実測と NSG の全拒否規則も確認済みです。

DTS の public 接続を有効にした後、手元の PC からダッシュボードの照会 API を呼び、
`default` / `workshop` の両方で Entra 認証付きの照会が HTTP 200 となり、
保存済みのオーケストレーションを取得できることを確認しました。匿名の照会は HTTP 404 でした。
正式な `dashboardUrl` と Entra 認証を使い、ブラウザーでも両 task hub の一覧表示を確認しました。
Functions の順次処理、fan-out/fan-in、CSV の再実行と Internet 向け接続の timeout も再確認しました。

監視は private ingestion / local auth 無効の設定で Application Insights の request telemetry
が取り込まれていることを ARM の集計メトリックで確認しました。
public query を許可した後は、手元の PC から両リソースへ Entra 認証でクエリし、
request 集計と Queue 検証の trace 3件を確認しました。匿名クエリは両方とも401です。
private 接続元からのクエリ、Queue trigger の実メッセージ処理、業務 MI の DDL 拒否の
負のテスト、scale-to-zero からのコールドスタートと負荷による scale-out は未確認です。
デプロイ後は HTTP 応答に加え、Durable の `runtimeStatus = Completed` と期待出力、
CSV の処理件数を確認します。

追加検証では4アプリの全16関数を実行しました。上記の HTTP / Durable / 初期化に加え、
`LogQueueMessage` は公式の
[非 HTTP 関数の手動実行 API](https://learn.microsoft.com/azure/azure-functions/functions-manually-run-non-http)
で3回起動しました。他の関数を呼ばない時間帯を区切り、対象 app の Function execution
メトリック3件と成功 request 3件・失敗0件で完了を確認しています。
既存 master key は管理 API 呼び出し時にメモリ内だけで使用し、出力・保存・変更しません。
この手動実行は Storage Queue への実メッセージ投入によるトリガー確認とは区別します。

`scripts\Test-AzureDeployment.ps1` は一時 Function key を使い、HTTP 認証、Durable の
期待出力、CSV 再実行とネットワークを検証します。初期化アプリのキー認証付き
`POST /api/deployment/verify` は入力を受け付けず、自身の private DNS と固定した
`www.microsoft.com:443` への新規 TCP 接続の timeout を確認します。
任意の宛先を指定するプロキシではありません。Internet 向けの新規 outbound の拒否は、
この固定宛先への実測結果と、デプロイ済み NSG の全拒否規則を組み合わせて確認します。
Azure の順次処理は、各 String Activity 入力の JSON 引用符・エスケープを保持した
3段の期待値を計算して比較します。
HTTP status の `historyEvents` は成功 Activity を `TaskCompleted` / `FunctionName`
として返すため、CSV の3ページ確認はその形式で行います。
検証中にプロセスを強制終了した場合は、対象関数に残った `azd-verify-` キーを削除してください。

Storage / PostgreSQL の public network は無効です。手元の PC から PostgreSQL /
Storage を直接操作する場合は VNet への接続経路と private DNS が必要です。
サンプルの運用性を優先し、Log Analytics と Application Insights は
`publicNetworkAccessForQuery: Enabled` として、手元の PC / Azure Portal からの
クエリだけを許可します。匿名公開ではなく、Entra 認証と閲覧権限が必要です。
取り込み側の public access とローカル認証は無効のままにします。
AMPLS の ingestion / query access mode も `PrivateOnly` のままです。
AMPLS の query access mode は接続済み VNet から AMPLS 外の監視リソースへの
到達性を制御する別設定なので、public client からのクエリのために `Open` にしません。
Functions の Private Endpoint 経由の取り込みと Internet 拒否も維持します。
AMPLS の DNS はリンクした VNet 内の他の Monitor 接続にも影響し得るため、
既存の共有 VNet/AMPLS にそのまま取り込まないでください。

これはコストを抑えたサンプルです。PostgreSQL は Burstable・HA なし・7日バックアップ、
Storage は LRS です。Private Endpoint、PostgreSQL、監視等には継続課金があります。
本番環境では HA/DR、利用者向け Entra 認証、アラート、初期化アプリの運用制限を別途設計してください。
