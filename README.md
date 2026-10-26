# java-azure-functions-durable-functions-samples

Java Azure Functions と Durable Functions のサンプル集です。
ローカルでは Aspire から Java の Functions host、Azurite、Durable Task Scheduler
（DTS）Emulator、PostgreSQL をまとめて起動します。
Aspire の AppHost は C#、Functions 本体は Java です。
**まずローカルで試す場合、Azure へのサインインやクラウドリソースは不要です。**

## サンプル一覧

| サンプル | 学べること | ローカル URL | リクエスト例 |
|---|---|---|---|
| [http-check-file](samples/http-check-file) | HTTP 入力検証と Queue trigger | `http://localhost:7072` | [requests.http](samples/http-check-file/requests.http) |
| [durable-functions](samples/durable-functions) | Activity の順次実行、fan-out/fan-in、中断・再開 | `http://localhost:7076` | [requests.http](samples/durable-functions/requests.http) |
| [durable-csv-batch](samples/durable-csv-batch) | Blob の CSV を分割・並列処理し、PostgreSQL に冪等に登録 | `http://localhost:7075` | [requests.http](samples/durable-csv-batch/requests.http) |

初めての場合は、HTTP の `CheckFile` → 最小構成の Durable Functions → CSV バッチの順に
試すと、単純なリクエスト処理から状態を持つバッチ処理へ進めます。
各サンプルは共通のルート AppHost から起動します。

## 目次

- [サンプルの動かし方（ローカル）](#サンプルの動かし方ローカル)
- [Azure へのデプロイ（Bicep + azd）](#azure-へのデプロイbicep--azd)

## サンプルの動かし方（ローカル）

以下のコマンドは Windows / PowerShell 向けです。
**すべてリポジトリルートから実行してください。**

### 1. 前提条件を準備する

- .NET 10 SDK と Aspire CLI
- インストール済み JDK を指す `JAVA_HOME` を設定した JDK 11 以降
- Azure Functions Core Tools v4（`func` を PATH に設定）
- Linux コンテナーエンジンを使用する Docker Desktop

Maven は各サンプルに含まれる Maven Wrapper を使用するため、別途インストールは不要です。
Docker Desktop を起動してから次に進みます。初回起動には依存パッケージと
コンテナーイメージのダウンロードが必要です。

### 2. Aspire で起動する

3つのサンプルと依存サービスをまとめて起動します。

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

起動時に案内される Aspire Dashboard でリソースの状態とログを確認できます。
**`aspire wait` の成功だけでは Functions host の起動完了を保証しません。**
初回は Maven のビルドが続くため、各 Functions のログで関数の一覧と
host の起動完了を確認してからリクエストを送ってください。

`.http` ファイルは VS Code の REST Client 拡張機能など、対応する HTTP クライアントで
実行します。ローカルでは Functions Core Tools が通常 Function key の認証を強制しないため、
これらのリクエストはキーなしで呼び出せます。Azure 上では Function key が必要です。

### 3. HTTP と Queue trigger を試す

[`http-check-file/requests.http`](samples/http-check-file/requests.http) のリクエストを実行します。

- `Check a file name`: `sample.csv` を送信すると HTTP 200 と
  `Accepted file name: sample.csv` を返します。
- `Reject an empty file name`: 空の本文を送信すると HTTP 400 と
  `blobName is required` を返します。

`CheckFile` はファイル名を受け付けるだけで、Blob の存在確認や CSV 処理は行いません。
Queue trigger は別の関数です。Aspire Dashboard の `queues` リソースで
**Send queue message** コマンドからメッセージを送信し、`http-check-file` のログに
`Queue message received: ...` が出ることを確認します。

### 4. 最小構成の Durable Functions を試す

順次パターンと fan-out/fan-in パターンを含み、DTS Emulator の `default` task hub を使用します。

公開エンドポイントは `http://localhost:7076` です。
[`durable-functions/requests.http`](samples/durable-functions/requests.http) で
Sequential または Fan-out を実行します。
開始レスポンスに含まれる `statusQueryGetUri` でステータスを確認し、
`runtimeStatus` が `Completed` になるまで待ちます。
`.http` ファイル内の対応するステータス照会リクエストを繰り返し実行できます。

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

### 5. CSV バッチを試す

Azurite の CSV をページ分割し、DTS Emulator の `workshop` task hub で
ページごとの Activity を並列実行して、PostgreSQL に登録します。
AppHost の起動時に、[`sample-input.csv`](samples/durable-csv-batch/sample-input.csv) が
Azurite の `batch-inputs` コンテナーにアップロードされます。手動アップロードは不要です。

1. [`durable-csv-batch/requests.http`](samples/durable-csv-batch/requests.http) の
   `Start the CSV batch` を実行します。既定のリクエストは `batchId: workshop-001`、
   `pageSize: 50`、`maxParallel: 2` です。
2. `Check the orchestration status` を繰り返し実行し、`runtimeStatus: Completed` と
   `output` の `successCount`（正常行数）、`failureCount`（入力検証で不正となった行数）を確認します。
   不正行が含まれていても、バッチ自体は正常行を登録して完了します。
3. 同じ `batchId` のまま開始リクエストを再実行すると、新しいオーケストレーションが
   開始されますが、処理済みページの結果を再利用し、DB への重複登録を防ぎます。
   入力 CSV やページ分割の条件を変えて試す場合は、別の `batchId` を指定してください。

### 6. 停止する

一通り試したら、CLI で起動した AppHost を停止します。

```powershell
aspire stop --non-interactive --apphost .\apphost.cs
```

## Azure へのデプロイ（Bicep + azd）

**ここからは Azure サブスクリプションと課金対象のリソースが必要です。**
ローカルで試すだけなら、このセクションの作業は不要です。
デプロイする場合は、以下の構成と制限を確認してから
[前提条件とコマンド](#前提条件とコマンド)へ進んでください。

- [Azure の構成とネットワーク](#azure-の構成とネットワーク)
- [Durable Task Scheduler ダッシュボード](#durable-task-scheduler-ダッシュボード)
- [認証と DB 初期化](#認証と-db-初期化)
- [前提条件とコマンド](#前提条件とコマンド)
- [Azure 実行用成果物の作成](#azure-実行用成果物の作成)
- [Azure 上で確認する項目と制限](#azure-上で確認する項目と制限)

ローカルでは Aspire で各サービスを起動します。
Azure へのデプロイには `azure.yaml` と `infra\main.bicep` を使用します。
DTS の public 接続と Azure Monitor 系の public query 設定もこの Bicep に含み、
`azd up` でアプリとまとめてデプロイします。

### Azure の構成とネットワーク

| 対象 | Azure 用の設定 |
|---|---|
| Function Apps | Java 11 / Linux Flex Consumption。既存3アプリと独立した `db-initializer` |
| HTTP / SCM | public HTTPS。TLS 1.2 以上、FTP 無効、SCM/FTP の基本認証無効 |
| Storage | アプリごとの host/deployment Storage と業務用 Storage。Private Endpoint、public network と Shared Key を無効化 |
| Durable Task Scheduler | Consumption、2 task hubs、Functions は Private Endpoint 経由。ダッシュボードとデータ API は public 接続を許可、Entra 認証・RBAC 必須 |
| PostgreSQL | Private Endpoint、public network 無効、Entra 認証のみ |
| Application Insights / Logs | AMPLS 経由の private ingestion。クエリのみ public を許可、Entra 認証・RBAC 必須、ローカル認証無効 |
| Functions outbound | VNet integration。Private Endpoint subnet の TCP 443/5432 とプラットフォーム DNS だけを許可し、残りの新規 outbound を NSG で拒否 |

**HTTP の入口は public、Functions から依存サービスへの接続は private です。**
Storage / PostgreSQL を手元の PC から直接操作するには、VNet への接続経路と private DNS が必要です。

<details>
<summary>ネットワークの詳細：outbound 制限・DNS・Functions 自身への接続</summary>

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

`AzurePlatformDNS` service tag は Deny 専用なので Allow ルールを作らず、
Azure 提供 DNS の標準の非フィルタ動作を維持しています。

</details>

### Durable Task Scheduler ダッシュボード

1. Azure Portal で scheduler の task hub（`default` または `workshop`）を開きます。
2. ダッシュボードのリンクを開き、Entra 認証でサインインします。
3. オーケストレーションの一覧・状態・履歴を確認します。

| 項目 | 設定・注意点 |
|---|---|
| 接続 | 手元の PC から使えるよう、ダッシュボードとデータ API の public 接続を許可。どちらも Entra 認証・RBAC 必須 |
| 利用者の権限 | scheduler に限定した `Durable Task Data Contributor`。閲覧だけでなく、作成・停止・削除も可能 |
| 利用者の指定 | azd が `AZURE_PRINCIPAL_ID` の Object ID を Bicep に渡す |
| Functions からの接続 | private DNS と Private Endpoint を使用。Internet 向け outbound 拒否は維持 |

<details>
<summary>接続 URL の詳細と「Task hub not found」の対処</summary>

Portal のリンクは task hub の ARM プロパティ `dashboardUrl` でも取得できます。
URL には subscription / scheduler / task hub のパスと `endpoint` / `tenantId` が含まれ、
対象の task hub と Entra 認証先が設定されます。

`/?endpoint=...&taskhub=...` の形式で開くと、認証なしの接続がブラウザーに保存されます。
正式な URL を開き直しても設定が残り、`Task hub not found` になる場合があります。

1. ダッシュボードの Home で対象の接続カードを開きます。
2. `Delete endpoint` → `Remove Entire Endpoint` を選びます。
3. Portal の正式なリンクを開き直します。

削除するのはブラウザーの保存済み接続だけです。Azure の scheduler や task hub は保持されます。

</details>

### 認証と DB 初期化

**DB 初期化は専用アプリだけが行い、業務アプリには DDL 権限を与えません。**

| 対象 | 認証・権限 |
|---|---|
| 各 Function App | それぞれ別の User Assigned Managed Identity（MI） |
| サービス間接続 | host/deployment Blob、Queue、業務 Blob、DTS、PostgreSQL、テレメトリは MI 認証 |
| DB 初期化用 MI | この MI だけを PostgreSQL Entra administrator に設定。スキーマ作成と業務用 MI への権限付与を担当 |
| 業務用 MI | `batch_runtime` 経由で CONNECT / schema USAGE / 対象3テーブルの SELECT・INSERT・UPDATE のみ |
| JDBC | `verify-full` + `DefaultJavaSSLFactory`。JDK の trust store で証明書チェーンとホスト名を検証 |
| 初期化 HTTP | `postdeploy` hook が一時 Function key で `POST /api/database/initialize` を呼ぶ。入力パラメーター・任意 SQL は不可 |

> 初期化 HTTP の Function key は、このサンプルでの明示的な例外です。
> Entra 認証の代替となる本番向けの利用者認証ではありません。
> hook を強制終了した場合は、`azd-bootstrap-` で始まる残存キーを手動で削除してください。

<details>
<summary>初期化の詳細：再実行・トランザクション・一時キーの扱い</summary>

同じ初期化は再実行でき、既存ロールの Object ID 不一致では停止します。
Entra ロールの作成・検証は、管理関数が存在する `postgres` DB で先にコミットします。
その後の `workshop` DB の DDL と権限設定も別のトランザクション／advisory lock で保護し、
途中失敗からの再実行に対応します。
サンプル CSV の Blob が存在する場合は、その内容を保持します。
存在しない場合にサンプル CSV を作成します。
DB コミット後に Blob 準備が失敗した場合も、hook を再実行できます。

hook はデプロイ主体の Azure 管理権限で一時的な function-scoped key を作成し、
メモリ内からヘッダーで送信した後、`finally` で削除します。キーをソース、azd output、
URL、ログへ保存しません。作成・削除に失敗した場合は hook も失敗します。
作成 API がキーの値を返さない場合にも対応するため、作成後に対象キーを別途読み取ります。
SQL 失敗時はキー認証済み応答に SQLSTATE だけを返し、SQL 本文や資格情報は返しません。

</details>

### 前提条件とコマンド

| 確認項目 | 必要なもの |
|---|---|
| ツール | ローカル用の前提条件 + Azure CLI、Azure Developer CLI、PowerShell 7、.NET 8 向け拡張機能を build できる .NET SDK |
| ビルド端末の通信 | Maven Central / NuGet への到達性 |
| デプロイ主体の権限 | RG 作成、リソース作成、RBAC 付与、Functions key 管理 |
| リージョン・quota | Java Flex、DTS Private Link、PostgreSQL が利用可能であること |
| Resource providers | `Microsoft.Web`、`Microsoft.App`、`Microsoft.Storage`、`Microsoft.Network`、`Microsoft.DurableTask`、`Microsoft.DBforPostgreSQL`、`Microsoft.Insights`、`Microsoft.OperationalInsights`、`Microsoft.ManagedIdentity` |

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

| 操作 | 動作 |
|---|---|
| `azd provision --preview` | デプロイ前にインフラの変更予定を確認 |
| `azd up` | 初回はこちらを使用。インフラを作成し、4アプリを package/deploy 後、`postdeploy` hook で DB を初期化 |
| `azd hooks run postdeploy` | 対象環境を選択した上で、初期化だけを再実行 |

hook は初回起動・MI/RBAC 反映待ちや一時的な接続失敗・timeout を、最大10回の HTTP 試行内で
再試行します。**失敗を回避するために public access や Shared Key を有効にしないでください。**

### Azure 実行用成果物の作成

`azd up` の package は Maven の `azure` profile を使用します。
azd が渡す `AZURE_ENV_NAME` により自動で有効になります。
デプロイせずに成果物だけをビルドする場合は、次を実行します。

```powershell
.\samples\durable-csv-batch\mvnw.cmd -f .\pom.xml -Pazure package
az bicep build --file .\infra\main.bicep --outfile .\infra\main.json
```

| 設定 | ローカル | Azure 用成果物 |
|---|---|---|
| Binding extensions | extension bundle | Linux 向けの .NET binding extensions を同梱 |
| 監視 | OTel agent | Application Insights Java agent を同梱 |
| `host.json` | ソースのローカル設定 | staging 内だけを Azure 用に変更 |
| ビルド | Maven Wrapper | Maven Wrapper + .NET 拡張機能ビルド。remote build は無効 |

> **成果物のビルド成功だけでは、Azure 上の閉域構成で動作することを保証しません。**
> explicit extension installation を使用していますが、公式の第一推奨は extension bundle です。
> Java + DTS + Flex + Internet 全拒否の組み合わせが公式に動作保証された構成ではありません。

<details>
<summary>ビルドの詳細と unit tests：agent の切り替え・Java 11 互換性・出力先の分離</summary>

Functions の staging 完了後に拡張機能を build し、`azure` profile が staging 内の
設定と依存ファイルを Azure 用に整えます。staging の `host.json` から
`extensionBundle` を除去し、ソースの `host.json` はローカル用の設定を保持します。
再 package 時には Functions staging を消去し、クラウド用 DLL の残存を防ぎます。
Azure 成果物からはローカル用 OTel agent と
`azure-functions-java-opentelemetry` invocation middleware を除去します。
このライブラリの 1.1.0 は例外時に middleware chain を再実行するため、
Durable の yield を通常終了に変えてしまう組み合わせがあります。ローカル用の依存関係は維持します。

Azure の Java 11 と API の互換性を保つため Maven は `release=11` を使用します。
新しい JDK で `source` / `target` だけを指定すると、Java 11 にない API の呼び出しが
コンパイルを通過し、クラウドで `NoSuchMethodError` になることがあります。

azd は複数アプリを並列に package するため、拡張機能の intermediate / build / output は
各アプリの `target` 配下に分離します。
Java 用の拡張機能ビルドには、通常の .NET SDK と ExtensionsMetadataGenerator を使います。

拡張機能の同梱方式は
[explicit extension installation](https://learn.microsoft.com/azure/azure-functions/functions-bindings-register#explicitly-install-extensions)
を参照してください。

| テスト | コマンド |
|---|---|
| Java unit tests | `.\samples\durable-csv-batch\mvnw.cmd -f .\pom.xml test` |
| 初期化 hook の retry／失敗／キー削除 | `pwsh -NoProfile -File .\scripts\tests\Initialize-Database.Tests.ps1` |

hook テストは mock の Azure CLI と HTTP 応答を使い、ビルド端末上で実行します。

</details>

### Azure 上で確認する項目と制限

デプロイ後は **HTTP 応答だけでなく、Durable の `Completed` と期待出力、CSV の処理件数**
まで確認してください。以下は既存の Japan East 実環境での確認記録です。
別の環境・リージョンでの動作を保証するものではありません。

#### 確認済み

| 対象 | 確認結果 |
|---|---|
| デプロイ・初期化 | `azd up` の provision、4アプリの deploy、初期化 hook が成功。hook の再実行も確認 |
| HTTP / Durable | 4アプリ再起動後のキー認証、順次処理の3段の期待出力、12件の fan-out/fan-in |
| CSV | 成功490件・不正10件・3ページ（`pageSize=200`）。同じ batch ID での再実行も確認 |
| ネットワーク・MI | Private Endpoint 13個が Approved。Storage / PostgreSQL の public network と Storage Shared Key は無効。VNet 統合と resource-scoped MI ロールを確認 |
| Internet outbound | 固定宛先への接続 timeout と、NSG の全拒否規則を確認。DTS public 接続の有効化後も再確認 |
| DTS ダッシュボード | 両 task hub の Entra 認証付き照会が HTTP 200、匿名照会は404。正式な `dashboardUrl` からブラウザーで一覧表示も確認 |
| 監視 | private ingestion / local auth 無効で request telemetry を ARM 集計メトリックから確認。public query 許可後、手元の PC から両監視リソースへ Entra 認証で request 集計・Queue の trace 3件を照会。匿名は401 |
| 関数の実行範囲 | 4アプリの全16関数を実行。Queue 関数は手動実行 API で3回起動し、成功3件・失敗0件 |

#### 未確認

| 対象 | 残っている確認 |
|---|---|
| 監視 | private 接続元からのクエリ |
| Queue trigger | Storage Queue への実メッセージ投入による起動（手動実行 API とは別） |
| DB 権限 | 業務 MI による DDL が拒否されることの負のテスト |
| スケール | scale-to-zero からのコールドスタート、負荷による scale-out |

#### デプロイ後の検証

[`scripts/Test-AzureDeployment.ps1`](scripts/Test-AzureDeployment.ps1) は一時 Function key を使い、
HTTP 認証、Durable の期待出力、CSV 再実行とネットワークを検証します。

> 検証を強制終了した場合は、対象関数に残った `azd-verify-` キーを手動で削除してください。

<details>
<summary>検証の詳細：Queue の手動実行・ネットワーク判定・Durable の出力比較</summary>

`LogQueueMessage` は公式の
[非 HTTP 関数の手動実行 API](https://learn.microsoft.com/azure/azure-functions/functions-manually-run-non-http)
で3回起動しました。他の関数を呼ばない時間帯を区切り、対象 app の Function execution
メトリック3件と成功 request 3件・失敗0件で完了を確認しています。
既存 master key は管理 API 呼び出し時にメモリ内だけで使用し、出力・保存・変更しません。
この手動実行は Storage Queue への実メッセージ投入によるトリガー確認とは区別します。

初期化アプリのキー認証付き
`POST /api/deployment/verify` は入力を受け付けず、自身の private DNS と固定した
`www.microsoft.com:443` への新規 TCP 接続の timeout を確認します。
任意の宛先を指定するプロキシではありません。Internet 向けの新規 outbound の拒否は、
この固定宛先への実測結果と、デプロイ済み NSG の全拒否規則を組み合わせて確認します。
Azure の順次処理は、各 String Activity 入力の JSON 引用符・エスケープを保持した
3段の期待値を計算して比較します。
HTTP status の `historyEvents` は成功 Activity を `TaskCompleted` / `FunctionName`
として返すため、CSV の3ページ確認はその形式で行います。

</details>

#### 監視・運用上の制限

| 項目 | 制限・注意点 |
|---|---|
| 監視の public query | 手元の PC / Portal から照会できるよう `publicNetworkAccessForQuery: Enabled`。Entra 認証・閲覧権限は必須 |
| 監視の取り込み | public ingestion とローカル認証は無効。Functions は Private Endpoint 経由で送信し、Internet 拒否も維持 |
| AMPLS | ingestion / query access mode は `PrivateOnly`。public client の照会のために `Open` にしない |
| 既存 VNet への組み込み | AMPLS の DNS は同じ VNet 内の他の Monitor 接続にも影響するため、既存の共有 VNet/AMPLS にそのまま取り込まない |
| コスト優先の構成 | PostgreSQL は Burstable・HA なし・7日バックアップ。Storage は LRS |
| 継続課金 | Private Endpoint、PostgreSQL、監視等は利用状況に応じて課金 |
| 本番での追加設計 | HA/DR、利用者向け Entra 認証、アラート、初期化アプリの運用制限 |

AMPLS の query access mode は、接続済み VNet から AMPLS 外の監視リソースへの到達性を
制御する設定です。監視リソースへの public client からのクエリ許可とは別の設定です。
