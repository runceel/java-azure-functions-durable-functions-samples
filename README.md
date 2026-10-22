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
Fan-out/fan-in パターンは、本文の項目数（1～20、省略時は5）分の Activity を並列実行し、
`Processed "Item 1" | Processed "Item 2" | ...` の形式で結果を結合します。

検証が完了したら、CLI で起動したセッションを停止します。

```powershell
aspire stop --non-interactive --apphost .\apphost.cs
```
