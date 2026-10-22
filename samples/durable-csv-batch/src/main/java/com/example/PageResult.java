package com.example;

/**
 * ページ単位またはバッチ全体の処理件数です。
 */
public class PageResult {
    /** 正常に登録または処理できた行数。 */
    public int successCount;
    /** 検証エラーまたは却下された行数。 */
    public int failureCount;
}
