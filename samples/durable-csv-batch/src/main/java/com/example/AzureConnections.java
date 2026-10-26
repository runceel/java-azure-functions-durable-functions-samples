package com.example;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.ManagedIdentityCredential;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

final class AzureConnections {
    private AzureConnections() {
    }

    static boolean usesManagedIdentity(Map<String, String> environment, String setting) {
        String mode = environment.getOrDefault(setting, "local");
        if (!mode.equals("local") && !mode.equals("managedidentity")) {
            throw new IllegalStateException("Unsupported authentication mode for " + setting);
        }
        return mode.equals("managedidentity");
    }

    static BlobServiceClient blobService() {
        Map<String, String> environment = System.getenv();
        BlobServiceClientBuilder builder = new BlobServiceClientBuilder();
        if (usesManagedIdentity(environment, "AZURE_STORAGE_AUTHENTICATION")) {
            return builder.endpoint(required(environment, "AZURE_STORAGE_BLOB_ENDPOINT"))
                    .credential(Identity.CREDENTIAL)
                    .buildClient();
        }
        return builder.connectionString(required(environment, "AZURE_STORAGE_CONNECTION_STRING"))
                .buildClient();
    }

    static Connection databaseConnection() throws SQLException {
        Map<String, String> environment = System.getenv();
        Properties properties = new Properties();
        properties.setProperty("user", required(environment, "DB_USERNAME"));
        if (usesManagedIdentity(environment, "DB_AUTHENTICATION")) {
            AccessToken token = Identity.CREDENTIAL.getToken(new TokenRequestContext()
                    .addScopes("https://ossrdbms-aad.database.windows.net/.default"))
                    .block(Duration.ofSeconds(30));
            if (token == null) {
                throw new IllegalStateException("Managed identity did not return a PostgreSQL token.");
            }
            properties.setProperty("password", token.getToken());
            properties.setProperty("sslmode", "verify-full");
            properties.setProperty("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
        } else {
            properties.setProperty("password", required(environment, "DB_PASSWORD"));
        }
        properties.setProperty("connectTimeout", "15");
        properties.setProperty("socketTimeout", "60");
        return DriverManager.getConnection(required(environment, "DB_JDBC_URL"), properties);
    }

    static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is not set: " + name);
        }
        return value;
    }

    private static final class Identity {
        private static final ManagedIdentityCredential CREDENTIAL = new ManagedIdentityCredentialBuilder()
                .clientId(required(System.getenv(), "AZURE_CLIENT_ID"))
                .build();
    }
}
