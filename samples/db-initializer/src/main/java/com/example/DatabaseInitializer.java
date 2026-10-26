package com.example;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.ManagedIdentityCredential;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

final class DatabaseInitializer {
    private static final String RUNTIME_ROLE = "batch_runtime";

    void initialize() throws SQLException, IOException {
        Map<String, String> environment = System.getenv();
        String runtimeObjectId = UUID.fromString(required(environment, "DB_RUNTIME_OBJECT_ID")).toString();
        ManagedIdentityCredential credential = new ManagedIdentityCredentialBuilder()
                .clientId(required(environment, "AZURE_CLIENT_ID"))
                .build();
        AccessToken token = credential.getToken(new TokenRequestContext()
                .addScopes("https://ossrdbms-aad.database.windows.net/.default"))
                .block(Duration.ofSeconds(30));
        if (token == null) {
            throw new IllegalStateException("Managed identity did not return a PostgreSQL token.");
        }
        Properties properties = new Properties();
        properties.setProperty("user", required(environment, "DB_USERNAME"));
        properties.setProperty("password", token.getToken());
        properties.setProperty("sslmode", "verify-full");
        properties.setProperty("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
        properties.setProperty("connectTimeout", "15");
        properties.setProperty("socketTimeout", "60");
        // The Entra principal-management functions exist only in the postgres database.
        try (Connection connection = DriverManager.getConnection(
                required(environment, "DB_ADMIN_JDBC_URL"), properties)) {
            connection.setAutoCommit(false);
            try {
                initializeRuntimeRole(connection, runtimeObjectId);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
        try (Connection connection = DriverManager.getConnection(
                required(environment, "DB_JDBC_URL"), properties)) {
            connection.setAutoCommit(false);
            try {
                initializeSchema(connection);
                connection.commit();
            } catch (SQLException | IOException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
        seedSample(environment, credential);
    }

    static void initializeRuntimeRole(Connection connection, String runtimeObjectId) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '15s'");
            statement.execute("SET LOCAL statement_timeout = '45s'");
            statement.execute("SELECT pg_advisory_xact_lock(742193015)");
            ensureRuntimeRole(connection, runtimeObjectId);
        }
    }

    static void initializeSchema(Connection connection)
            throws SQLException, IOException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '15s'");
            statement.execute("SET LOCAL statement_timeout = '45s'");
            statement.execute("SELECT pg_advisory_xact_lock(742193015)");
            try (InputStream resource = DatabaseInitializer.class.getResourceAsStream("/schema.sql")) {
                if (resource == null) {
                    throw new IOException("The database schema resource is missing.");
                }
                String schema = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                for (String sql : schema.split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }
            statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            statement.execute("REVOKE ALL ON DATABASE workshop FROM PUBLIC");
            statement.execute("GRANT CONNECT ON DATABASE workshop TO batch_runtime");
            statement.execute("GRANT USAGE ON SCHEMA public TO batch_runtime");
            statement.execute("GRANT SELECT, INSERT, UPDATE ON TABLE "
                    + "batch_page_runs, imported_records, rejected_rows TO batch_runtime");
        }
    }

    private static void ensureRuntimeRole(Connection connection, String objectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM pg_catalog.pgaadauth_list_principals(false) AS principals(role_name) WHERE role_name = ?")) {
            statement.setString(1, RUNTIME_ROLE);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    if (!objectId.equalsIgnoreCase(result.getString("objectId"))) {
                        throw new SQLException("The runtime role is mapped to a different identity.");
                    }
                    return;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM pg_catalog.pgaadauth_create_principal_with_oid(?, ?, 'service', false, false)")) {
            statement.setString(1, RUNTIME_ROLE);
            statement.setString(2, objectId);
            statement.execute();
        }
    }

    private static void seedSample(Map<String, String> environment, ManagedIdentityCredential credential)
            throws IOException {
        BlobClient blob = new BlobServiceClientBuilder()
                .endpoint(required(environment, "AZURE_STORAGE_BLOB_ENDPOINT"))
                .credential(credential)
                .buildClient()
                .getBlobContainerClient("batch-inputs")
                .getBlobClient("sample-input.csv");
        try (InputStream resource = DatabaseInitializer.class.getResourceAsStream("/sample-input.csv")) {
            if (resource == null) {
                throw new IOException("The sample CSV resource is missing.");
            }
            byte[] content = resource.readAllBytes();
            try {
                blob.upload(new java.io.ByteArrayInputStream(content), content.length, false);
            } catch (BlobStorageException failure) {
                if (!BlobErrorCode.BLOB_ALREADY_EXISTS.equals(failure.getErrorCode())) {
                    throw failure;
                }
                // A repeated deployment must not replace an existing input file.
            }
        }
    }

    static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is not set: " + name);
        }
        return value;
    }
}
