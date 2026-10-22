package com.example;

/**
 * 処理対象ページを一意に識別する参照情報です。
 */
public class PageRef {
    /** バッチ ID。 */
    public String batchId;
    /** ページ番号。 */
    public int pageId;
    /** ページ CSV の Blob 名。 */
    public String blobName;
}
