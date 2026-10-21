---
name: java-functions-aspire
description: >-
  Use this skill whenever creating, modifying, running, or troubleshooting Java
  Azure Functions or Java Durable Functions with Aspire in this repository,
  even if the user does not name the skill. Triggers: Java Functions,
  Java Durable Functions, Aspire で Functions を起動, DTS Emulator,
  Maven Wrapper, Functions host が起動しない, Durable の動作確認.
  Covers repository-specific Maven launch, ports, Azurite, Durable Task Scheduler
  wiring and end-to-end verification. Not for .NET Functions or cloud deployment.
---

# Java Functions + Aspire

このリポジトリ固有の Java Azure Functions / Durable Functions の手順です。
公式 `aspireify` などとは別のカスタム Skill として管理し、公式 Skills は編集しません。
CLI 更新後も本 Skill とルート `AGENTS.md` を維持します。

## 前提条件

.NET SDK、Aspire CLI、JDK と有効な `JAVA_HOME`、Functions Core Tools v4、
Linux コンテナを使える Docker が必要。Maven は各アプリの Wrapper を使います。
ローカル検証に Azure 認証や MCP サーバーは不要です。
バージョン、チェックコマンド、配線の根拠は
[Java Functions 手順 § 1](references/JAVA_FUNCTIONS.md#1-前提条件と作業場所) を参照。

## 適用する依頼の例

| 依頼 | 実行内容と成功条件 |
|---|---|
| 「Java の単純な HTTP / Blob + Queue Functions を追加して」 | Maven と AppHost を配線し、HTTP の status/body または処理後 Blob の内容まで確認 |
| 「最小 Durable Functions を Aspire で動かして」 | 既存サンプルを起動し、Starter の HTTP 202、`Completed`、期待 output を確認 |
| 「Healthy なのに Functions にアクセスできない」 | resource 状態と実行ログを調査し、Maven、Core Tools、proxy、DTS のどの段階の失敗かを特定 |

## ワークフロー

1. ルート `AGENTS.md`、`apphost.cs`、対象アプリの `pom.xml` / `host.json` /
   Java コードを読み、実装済み範囲とユーザーの依頼を確認する。
   詳細は [手順 § 対象と確認済みの範囲](references/JAVA_FUNCTIONS.md#対象と確認済みの範囲)。
2. 作業に必要な箇所を [Java Functions 手順](references/JAVA_FUNCTIONS.md) から読む。
   配線変更は § 2、起動は § 3、Durable 検証は § 4、通常 Functions の追加は § 5、
   失敗対応・停止は § 6。初回の追加／起動は全体を読む。
3. 公式 Skills のうち今回必要なものだけを併用する。
   AppHost 配線の一般規則は `aspireify`、ライフサイクルと editor/CLI の選択は
   `aspire-orchestration`、観測は `aspire-monitoring`。
   本 Skill は Java 固有の条件を補足し、安全規則を迂回しない。
4. 前提条件を確認してから依頼された変更／実行を行う。
   既存 AppHost を再初期化せず、未知の API は `aspire docs` で調べる。
5. `aspire wait` 後もログを確認する。`Healthy` だけで成功扱いしない。
   Durable では現行サンプルの `Completed` と `Hello, "Durable Functions"!`、
   通常 Functions では依頼された入出力契約まで検証する。
6. 検証用に自分で起動した AppHost とログ購読は終了時に停止する。
   既存の稼働やユーザーが継続利用する環境を無断停止しない。

## 主要な注意点

| 症状／誤り | 対応 |
|---|---|
| `mvnw.cmd` が見つからない | Wrapper directory を既存 PATH に追加する現行の配線を維持 |
| staging directory がない | `package` → `azure-functions:run` の順序を維持 |
| `Healthy` の後に終了 | Maven プロセスの正常性と host readiness は別。ログの末尾まで確認 |
| 7071 競合／HTTP timeout | 内部 `targetPort: 7071` と動的な公開 port を区別 |
| Durable 注釈の import エラー | `com.microsoft.durabletask.azurefunctions` を使う |
| DTS に接続できない | 現在の task hub 接続式と `azureManaged` provider を照合 |

詳細なエラー対応と停止手順は [手順 § 6](references/JAVA_FUNCTIONS.md#6-失敗の切り分けと後片付け)。

## 出力形式

変更内容と、実際に確認した結果／未確認事項、停止済みかを簡潔に報告する。
例: 「Starter HTTP 202、Completed、output は `Hello, "Durable Functions"!`。
検証用 AppHost は停止済み。」
接続情報、管理用 URL のキー、マシン固有の JDK パスを記録しない。

## Post-Run Reflection

再発した問題や追加条件があれば、実測した事実と推測を区別して
`references/JAVA_FUNCTIONS.md` に追記する。
配線や出力の変更時は同じ依頼例で再検証し、README と `AGENTS.md` の説明を揃える。
公式 Skills を更新先にしない。新たな一般-purpose Skill を増やさない。
このリポジトリに存在しない plugin manifest や core Skill への依存は追加しない。

## References

| 参照 | 読むとき |
|---|---|
| [Java Functions 手順](references/JAVA_FUNCTIONS.md) | Java Functions の追加、起動、検証、失敗調査 |
