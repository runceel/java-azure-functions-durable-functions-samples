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

### HTTP `CheckFile`

独立した HTTP サンプルをビルドして実行します。

```powershell
.\samples\http-check-file\mvnw.cmd -B -ntp package
aspire start --non-interactive --apphost .\samples\http-check-file\apphost.cs
aspire wait http-check-file --non-interactive --apphost .\samples\http-check-file\apphost.cs
```

リクエストを実行するには、VS Code の REST Client 拡張機能または `.http` ファイルを
サポートするエディターで `samples\http-check-file\requests.http` を開き、いずれかの
リクエストを実行します。Aspire は Functions ホストの内部ポート 7071 を、リクエスト
ファイルで使用する固定のローカルエンドポイント `http://localhost:7072` にマッピングします。
ローカル実行では、関数が `AuthorizationLevel.FUNCTION` を使用していても、Core Tools は
デフォルトで HTTP 認証を強制しません。デプロイされた関数では function key が必要です。
別の HTTP クライアントを使用する場合は、次のコマンドで現在の Aspire エンドポイントを取得します。

```powershell
aspire describe --format Json --apphost .\samples\http-check-file\apphost.cs
```

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

### Durable CSV バッチ

このバッチサンプルは、`batch-inputs` Blob コンテナーの CSV をページに分割し、
有効な行を PostgreSQL に登録し、無効な行を別途記録します。
PostgreSQL のデータボリュームは AppHost 再起動後も保持されます。
論理的な実行ごとに新しい `batchId` を使用してください。完了したページのマーカーにより、
同じバッチの Activity 再試行は冪等になります。

```powershell
.\samples\durable-csv-batch\mvnw.cmd -B -ntp package
aspire start --non-interactive --apphost .\samples\durable-csv-batch\apphost.cs
aspire wait durable-csv-batch --non-interactive --apphost .\samples\durable-csv-batch\apphost.cs
```

#### VS Code でデバッグする

バッチ AppHost は、ポート `5005` で Java デバッグを有効にした Functions ホストを起動します。
Java Extension Pack をインストールした VS Code でこのリポジトリを開き、
`samples\durable-csv-batch\src\main\java` にブレークポイントを設定します。
次に、**実行とデバッグ**（F5）から `Attach to Durable CSV Functions` 設定を開始します。
`samples\durable-csv-batch\requests.http` からリクエストを送信するとブレークポイントに到達します。
デバッグリスナーはローカル開発専用です。

AppHost は起動時に `sample-input.csv` を `batch-inputs/sample-input.csv` として
ローカル Azurite インスタンスへ自動投入します。Azure Storage Explorer での手動アップロードは不要です。
Aspire は実行時に Java アプリへ Emulator の接続文字列を渡すため、開発用アカウントキーを
リポジトリコード内に重複して保存する必要はありません。

入力投入後、`samples\durable-csv-batch\requests.http` を開きます。
**Start the CSV batch** を実行し、続けて **Check the orchestration status** を
レスポンスの `runtimeStatus` が `Completed` になるまで繰り返し実行してください。
新しい論理入力を開始する場合は `@batchId` を変更します。同じ ID を再使用すると、
意図的に冪等な再実行動作が確認できます。
このリクエストファイルはポート 7072 の固定 Aspire エンドポイントを呼び出します。
ルートの統合 AppHost を使用する場合は、`requests.http` の `@baseUrl` を
`http://localhost:7075` に変更してください。両方の Java Functions ホストが内部ポートを使用するため、
ワークショップ AppHost は同時に1つだけ実行してください。

開始リクエストの本文は次のとおりです。

```json
{
  "batchId": "workshop-001",
  "blobName": "sample-input.csv",
  "pageSize": 2,
  "maxParallel": 2
}
```

ステータスリクエストは、直前の開始レスポンスに含まれる `statusQueryGetUri` を使用します。
同梓の入力は、4行が正常に登録され、1行が却下される想定です。
初回インポート時に PostgreSQL テーブル `imported_records`、`rejected_rows`、
`batch_page_runs` が作成されます。CSV またはヘッダーが不正な場合、または
インフラストラクチャエラーが発生した場合はオーケストレーションが失敗します。
行の検証エラーは記録され、他の行の処理は継続します。

#### pgAdmin で結果を確認する

Aspire ダッシュボードの `pgadmin` リソースを開くか、次のコマンドで現在の URL を確認します。

```powershell
aspire describe --format Json --non-interactive --apphost .\apphost.cs
```

pgAdmin に接続すると、`postgres` 配下のデータベースから `batchdb` / `workshop`
を開いて確認できます。起動直後に pgAdmin が `Unhealthy` の場合は、数秒待ってから
再度リソースを開いてください。

初回投入後は、Query Tool で次のSQLを実行できます。

```sql
SELECT COUNT(*) AS valid_count FROM public.imported_records;
SELECT COUNT(*) AS rejected_count FROM public.rejected_rows;
SELECT COUNT(*) AS completed_pages FROM public.batch_page_runs;

SELECT batch_id, COUNT(*) AS row_count
FROM public.imported_records
GROUP BY batch_id
ORDER BY batch_id;
```

サンプル入力では正常な4件、不正な1件、完了ページが3件になる想定です。
同じ batch ID を再実行しても、登録件数とページ完了マーカーの件数が増えないことを確認できます。

起動した AppHost だけを停止します。

```powershell
aspire stop --non-interactive --apphost .\samples\http-check-file\apphost.cs
# バッチサンプルの場合:
aspire stop --non-interactive --apphost .\samples\durable-csv-batch\apphost.cs
```

SQL による行の登録とページ完了マーカーは、1つの PostgreSQL トランザクションでコミットされます。
オーケストレーターはウィンドウ内の同時実行数を制限し、各ページ Activity に対して
3回まで、5秒間隔の再試行ポリシーを適用します。

検証が完了したら、CLI で起動したセッションを停止します。

```powershell
aspire stop --non-interactive --apphost .\apphost.cs
```
