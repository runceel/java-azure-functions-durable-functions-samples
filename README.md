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

## ワークショップサンプル

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
