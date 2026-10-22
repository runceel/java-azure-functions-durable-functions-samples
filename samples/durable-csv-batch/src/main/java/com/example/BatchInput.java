package com.example;

/**
 * CSV バッチ実行に必要な入力パラメーターです。
 */
public class BatchInput {
    /** 実行を識別する論理 ID。 */
    public String batchId;
    /** 入力コンテナー内の Blob 名。 */
    public String blobName;
    /** 1 ページに分割する行数。 */
    public int pageSize;
    /** 同時実行するページ Activity の最大数。 */
    public int maxParallel;
}
