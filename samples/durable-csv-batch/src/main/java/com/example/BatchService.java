package com.example;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.core.util.BinaryData;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CSV ページの分割、Blob 参照、PostgreSQL への冪等な登録を担当します。
 */
public class BatchService {
    private static final String INPUT_CONTAINER = "batch-inputs";
    private static final String PAGE_CONTAINER = "batch-pages";
    private static final CSVFormat INPUT_CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .build();
    private static final CSVFormat OUTPUT_CSV_FORMAT = CSVFormat.DEFAULT;

    /**
     * 入力 CSV を検証し、設定ページサイズごとの Blob に分割します。
     *
     * @param input バッチ実行パラメーター
     * @return 分割したページ参照を含む処理計画
     */
    public BatchPlan preparePages(BatchInput input) {
        BlobServiceClient service = blobService();
        BlobContainerClient inputContainer = service.getBlobContainerClient(INPUT_CONTAINER);
        BlobClient inputBlob = inputContainer.getBlobClient(input.blobName);
        if (!inputBlob.exists()) {
            throw new IllegalArgumentException(
                    "Input blob does not exist in container " + INPUT_CONTAINER);
        }

        BlobContainerClient pageContainer = service.getBlobContainerClient(PAGE_CONTAINER);
        pageContainer.createIfNotExists();
        List<PageRef> pages = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(inputBlob.openInputStream(), StandardCharsets.UTF_8));
             CSVParser parser = INPUT_CSV_FORMAT.parse(reader)) {
            Map<String, Integer> headers = parser.getHeaderMap();
            // このサンプルが扱える列名を明示的に検証します。
            if (headers == null || !headers.containsKey("id") || !headers.containsKey("name")) {
                throw new IllegalArgumentException("CSV must contain id and name columns.");
            }

            List<String[]> rows = new ArrayList<>(input.pageSize);
            for (CSVRecord record : parser) {
                rows.add(new String[] {record.get("id"), record.get("name")});
                if (rows.size() == input.pageSize) {
                    pages.add(writePage(pageContainer, input.batchId, pages.size() + 1, rows));
                    rows.clear();
                }
            }
            if (!rows.isEmpty()) {
                pages.add(writePage(pageContainer, input.batchId, pages.size() + 1, rows));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read or split the input CSV.", e);
        }

        BatchPlan plan = new BatchPlan();
        plan.pages = pages.toArray(new PageRef[0]);
        return plan;
    }

    /**
     * ページを PostgreSQL トランザクション内で1回だけ登録します。
     * 完了済みページは保存済み件数を返し、再実行時の重複登録を防ぎます。
     *
     * @param page 処理対象ページ
     * @return ページの成功件数と失敗件数
     */
    public PageResult importPageOnce(PageRef page) {
        try (Connection connection = databaseConnection()) {
            connection.setAutoCommit(false);
            if (!AzureConnections.usesManagedIdentity(System.getenv(), "DB_AUTHENTICATION")) {
                createSchema(connection);
            }
            // -1 の完了マーカーは処理中または失敗中を表し、再実行時は同じ結果を再利用します。
            if (!claimPage(connection, page)) {
                PageResult existing = readPageResult(connection, page);
                connection.commit();
                return existing;
            }

            PageResult result = importRows(connection, page);
            updatePageResult(connection, page, result);
            connection.commit();
            return result;
        } catch (SQLException | IOException e) {
            throw new IllegalStateException(
                    "Unable to import page " + page.pageId + " for batch " + page.batchId, e);
        }
    }

    private PageRef writePage(
            BlobContainerClient container, String batchId, int pageId, List<String[]> rows) {
        String blobName = batchId + "/" + pageId + ".csv";
        try (StringWriter text = new StringWriter();
             CSVPrinter printer = new CSVPrinter(text, OUTPUT_CSV_FORMAT)) {
            printer.printRecord("id", "name");
            for (String[] row : rows) {
                printer.printRecord((Object[]) row);
            }
            printer.flush();
            container.getBlobClient(blobName)
                    .upload(BinaryData.fromString(text.toString()), true);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create a page CSV.", e);
        }
        PageRef page = new PageRef();
        page.batchId = batchId;
        page.pageId = pageId;
        page.blobName = blobName;
        return page;
    }

    /**
     * ページ Blob の CSV を読み、正常行と検証エラー行を分類します。
     *
     * @param connection 登録先 PostgreSQL 接続
     * @param page 処理対象ページ
     * @return 正常件数と検証失敗件数
     * @throws IOException ページ CSV の読み取りに失敗した場合
     * @throws SQLException 登録処理に失敗した場合
     */
    private PageResult importRows(Connection connection, PageRef page)
            throws IOException, SQLException {
        BlobClient blob = blobService().getBlobContainerClient(PAGE_CONTAINER)
                .getBlobClient(page.blobName);
        if (!blob.exists()) {
            throw new IllegalStateException("Page blob does not exist: " + page.blobName);
        }

        PageResult result = new PageResult();
        try (BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(blob.openInputStream(), StandardCharsets.UTF_8));
             CSVParser parser = OUTPUT_CSV_FORMAT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .build()
                     .parse(reader)) {
            for (CSVRecord record : parser) {
                int lineNumber = Math.toIntExact(record.getRecordNumber() + 1);
                String idText = record.get("id").trim();
                String name = record.get("name").trim();
                Integer recordId = parsePositiveInteger(idText);
                if (recordId == null || name.isBlank()) {
                    insertRejectedRow(
                            connection, page, lineNumber, idText, name, csvRow(record));
                    result.failureCount++;
                } else {
                    insertRecord(connection, page.batchId, recordId, name);
                    result.successCount++;
                }
            }
        }
        return result;
    }

    /**
     * 文字列が正の整数として解釈できるかを確認します。
     *
     * @param value 変換対象の文字列
     * @return 正の整数、または変換不能または 0 以下の場合は {@code null}
     */
    private static Integer parsePositiveInteger(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * CSV レコードを元の行形式に近い文字列へ変換します。
     *
     * @param record CSV レコード
     * @return CSV 形式の文字列
     * @throws IOException CSV 出力に失敗した場合
     */
    private static String csvRow(CSVRecord record) throws IOException {
        try (StringWriter text = new StringWriter();
             CSVPrinter printer = new CSVPrinter(text, OUTPUT_CSV_FORMAT)) {
            List<String> fields = new ArrayList<>();
            for (String field : record) {
                fields.add(field);
            }
            printer.printRecord(fields);
            printer.flush();
            return text.toString().stripTrailing();
        }
    }

    private static void createSchema(Connection connection) throws SQLException {
        try (java.io.InputStream resource = BatchService.class.getResourceAsStream("/schema.sql");
             Statement statement = connection.createStatement()) {
            if (resource == null) {
                throw new SQLException("The database schema resource is missing.");
            }
            // Serialize first-time schema creation across concurrent local Activities.
            statement.execute("SELECT pg_advisory_xact_lock(742193015)");
            String schema = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            for (String sql : schema.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        } catch (IOException e) {
            throw new SQLException("Unable to load the database schema.", e);
        }
    }

    private static boolean claimPage(Connection connection, PageRef page) throws SQLException {
        String sql =
                "INSERT INTO batch_page_runs(batch_id, page_id, success_count, failure_count) " +
                "VALUES (?, ?, -1, -1) ON CONFLICT (batch_id, page_id) DO NOTHING";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, page.batchId);
            statement.setInt(2, page.pageId);
            return statement.executeUpdate() == 1;
        }
    }

    private static PageResult readPageResult(Connection connection, PageRef page)
            throws SQLException {
        String sql = "SELECT success_count, failure_count FROM batch_page_runs " +
                "WHERE batch_id = ? AND page_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, page.batchId);
            statement.setInt(2, page.pageId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt("success_count") < 0) {
                    throw new SQLException("Existing page marker is incomplete.");
                }
                PageResult result = new PageResult();
                result.successCount = resultSet.getInt("success_count");
                result.failureCount = resultSet.getInt("failure_count");
                return result;
            }
        }
    }

    private static void insertRecord(
            Connection connection, String batchId, int recordId, String name)
            throws SQLException {
        String sql = "INSERT INTO imported_records(batch_id, record_id, name) " +
                "VALUES (?, ?, ?) ON CONFLICT (batch_id, record_id) " +
                "DO UPDATE SET name = EXCLUDED.name";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, batchId);
            statement.setInt(2, recordId);
            statement.setString(3, name);
            statement.executeUpdate();
        }
    }

    private static void insertRejectedRow(
            Connection connection, PageRef page, int lineNumber,
            String id, String name, String rawRow) throws SQLException {
        String reason = id.isBlank()
                ? "id is required"
                : name.isBlank() ? "name is required" : "id must be a positive integer";
        String sql = "INSERT INTO rejected_rows(batch_id, page_id, line_number, reason, raw_row) " +
                "VALUES (?, ?, ?, ?, ?) ON CONFLICT (batch_id, page_id, line_number) " +
                "DO UPDATE SET reason = EXCLUDED.reason, raw_row = EXCLUDED.raw_row";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, page.batchId);
            statement.setInt(2, page.pageId);
            statement.setInt(3, lineNumber);
            statement.setString(4, reason);
            statement.setString(5, rawRow);
            statement.executeUpdate();
        }
    }

    private static void updatePageResult(
            Connection connection, PageRef page, PageResult result) throws SQLException {
        String sql = "UPDATE batch_page_runs SET success_count = ?, failure_count = ? " +
                "WHERE batch_id = ? AND page_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, result.successCount);
            statement.setInt(2, result.failureCount);
            statement.setString(3, page.batchId);
            statement.setInt(4, page.pageId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Unable to persist the page completion marker.");
            }
        }
    }

    private static BlobServiceClient blobService() {
        return AzureConnections.blobService();
    }

    private static Connection databaseConnection() throws SQLException {
        return AzureConnections.databaseConnection();
    }
}
