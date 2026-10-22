# AI Agent 向けガイド

## 最初に読むもの

このリポジトリは Java Azure Functions と Durable Functions のローカルサンプルです。
Aspire の AppHost は C# ですが、Functions 本体は Java です。
**Java Functions に .NET 用の Functions 起動方法や ServiceDefaults を適用しないでください。**

1. [README](README.md) で現在の実装範囲を確認する。
2. Java Functions の作成・変更・起動・検証では、必ず独立したカスタム Skill
   [java-functions-aspire](.agents/skills/java-functions-aspire/SKILL.md) を読む。
   [詳細手順](.agents/skills/java-functions-aspire/references/JAVA_FUNCTIONS.md) が
   前提条件、Maven Wrapper、ポート、DTS 接続、実行確認、既知の失敗の正本です。
3. 作業に合う公式 Skill を必要な分だけ併用する。すべてを一括ロードしない。

| 作業 | Skill |
|---|---|
| Java Functions / Durable Functions 固有の手順（常に最初） | [.agents/skills/java-functions-aspire/SKILL.md](.agents/skills/java-functions-aspire/SKILL.md) |
| Aspire 全般の振り分け | [.agents/skills/aspire/SKILL.md](.agents/skills/aspire/SKILL.md) |
| Java Functions の追加、AppHost の配線・変更 | [.agents/skills/aspireify/SKILL.md](.agents/skills/aspireify/SKILL.md) |
| 起動・停止・再起動 | [.agents/skills/aspire-orchestration/SKILL.md](.agents/skills/aspire-orchestration/SKILL.md) |
| 起動失敗、ログ、接続の調査 | [.agents/skills/aspire-monitoring/SKILL.md](.agents/skills/aspire-monitoring/SKILL.md) |
| 初期化／デプロイ | `aspire-init` は AppHost がない場合のみ。`aspire-deployment` は明示されたデプロイ作業のみ |

Skill がツールで読み込めない環境では、このリポジトリ内の `SKILL.md` を直接読んでください。
ローカル手順は一般的な Skill の補足であり、ライフサイクルの安全規則は引き続き適用します。

## 現在の構成

| ファイル／リソース | 役割 |
|---|---|
| `apphost.cs` | file-based C# AppHost。Java Maven 実行、Azurite、DTS Emulator |
| `aspire.config.json` / `apphost.run.json` | CLI の AppHost 選択・起動設定 |
| `samples\durable-functions\pom.xml` | Java 依存関係、Functions の package/run |
| `samples\durable-functions\host.json` | Durable の `azureManaged` provider と extension bundle |
| `samples\durable-functions\src\main\java\com\example\hello\HelloStarter.java` | Hello 順次パターンの HTTP Starter |
| `samples\durable-functions\src\main\java\com\example\hello\HelloOrchestrator.java` | 3 Activity の順次実行オーケストレーター |
| `samples\durable-functions\src\main\java\com\example\hello\HelloActivities.java` | Hello 順次パターンの Activity |
| `samples\durable-functions\src\main\java\com\example\fanout\FanOutStarter.java` | fan-out/fan-in パターンの HTTP Starter |
| `samples\durable-functions\src\main\java\com\example\fanout\FanOutOrchestrator.java` | fan-out/fan-in オーケストレーター |
| `samples\durable-functions\src\main\java\com\example\fanout\FanOutActivity.java` | fan-out/fan-in の Activity |
| `samples\http-check-file` | Workshop HTTP `CheckFile` function |
| `samples\durable-csv-batch` | Workshop CSV split/import with Durable, Azurite, PostgreSQL |
| `storage` | Azurite。Blob 10000、Queue 10001、Table 10002 |
| `scheduler` / `default` | DTS Emulator と task hub |
| `durable-functions` | Maven Wrapper 経由の Java Functions host |

ワークショップサンプルは既存のルート AppHost から起動します。
`CheckFile` は単純な HTTP 例、`durable-csv-batch` は Blob の CSV を Durable
Activity で処理して PostgreSQL に登録する例です。Queue トリガーは使用しません。

## 必ず守ること

- コマンドはリポジトリのルートから実行する。現在の `apphost.cs` はそこを基準に
  `samples\durable-functions` を解決する。別ディレクトリから絶対 `--apphost` だけを渡さない。
- 既存の AppHost を使う。`aspire init` / `aspire agent init` を毎回実行しない。
  公式 Aspire Skills は CLI 更新時に上書きされるため編集しない。
  リポジトリ固有の知識は `java-functions-aspire` にのみ置き、
  更新時にもこのカスタム Skill と `AGENTS.md` を保持する。
- ライフサイクルは Aspire に任せる。CLI では `--apphost .\apphost.cs` と
  `--non-interactive` を指定する。VS Code の管理ツールがある場合の優先順位は
  `aspire-orchestration` に従う。
- `aspire wait` の `Healthy` は Maven プロセス起動の段階でも返る。
  **Functions host のログ、HTTP 応答、Durable の `Completed` と期待出力まで確認する。**
- Windows の Maven Wrapper 用 PATH、`package` → `azure-functions:run` の順序、
  `targetPort: 7071` を維持する。統合 AppHost の公開ポートは CheckFile が `7072`、
  CSV が `7075`、最小 Durable が `7076`。公開 port と内部 targetPort を同値にしない。
- スケジューラの接続先は task hub の `ConnectionStringExpression` から渡す。
  過去の実行で得た動的 URL・ポート・管理用キーをコードやドキュメントに固定しない。
- 未知の Aspire API は CLI の docs/API 検索で確認する。
  Java hosting は preview、DTS API は実験的。無断でバージョンを更新しない。
- 検証だけの起動は終了時に対象 AppHost を通常停止する。ユーザーが継続利用を
  指定した場合は稼働状況を報告する。`--force`、全 AppHost 停止、名前指定の一括 kill は使わない。
- ローカル実行に Azure サインインやクラウドリソースは不要。
  デプロイ、外部システムへの投稿、コミットは明示的な依頼なしに行わない。

## 完了判定と引き継ぎ

Durable は Starter の受付だけで成功にしない。返された `statusQueryGetUri` を使い、
`runtimeStatus = Completed` と現行サンプルの出力 `Hello, "Durable Functions"!` を確認する。
引用符を含む出力は現在の実測値です。意図した挨拶へ直す場合は別の挙動変更として扱います。

変更した配線やバージョン、確認できた結果／未確認事項、停止済みかを簡潔に報告する。
再利用できる新しい知見は `JAVA_FUNCTIONS.md` に反映し、セッション履歴だけに残さない。
