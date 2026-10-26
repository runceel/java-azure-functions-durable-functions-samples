package com.example;

import org.junit.jupiter.api.Test;

import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseInitializerTest {
    @Test
    void schemaAndSeedArePackaged() throws Exception {
        try (var schema = DatabaseInitializer.class.getResourceAsStream("/schema.sql");
             var csv = DatabaseInitializer.class.getResourceAsStream("/sample-input.csv")) {
            assertNotNull(schema);
            assertNotNull(csv);
            String sql = new String(schema.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(3, sql.split("CREATE TABLE IF NOT EXISTS", -1).length - 1);
            assertTrue(sql.contains("PRIMARY KEY (batch_id, page_id, line_number)"));
            assertTrue(new String(csv.readAllBytes(), StandardCharsets.UTF_8).startsWith("id,name"));
        }
    }

    @Test
    void missingSettingsFailInsteadOfUsingLocalCredentials() {
        assertThrows(IllegalStateException.class, () -> DatabaseInitializer.required(Map.of(), "AZURE_CLIENT_ID"));
    }

    @Test
    void initializerRequiresFunctionAuthorizationAndOnlyPost() throws Exception {
        var method = Functions.class.getMethod("initialize", HttpRequestMessage.class, ExecutionContext.class);
        HttpTrigger trigger = (HttpTrigger) method.getParameterAnnotations()[0][0];
        assertEquals(AuthorizationLevel.FUNCTION, trigger.authLevel());
        assertArrayEquals(new HttpMethod[]{HttpMethod.POST}, trigger.methods());
        assertEquals("database/initialize", trigger.route());
    }

    @Test
    void deploymentVerificationRequiresFunctionAuthorizationAndOnlyPost() throws Exception {
        var method = Functions.class.getMethod("verifyDeployment", HttpRequestMessage.class, ExecutionContext.class);
        HttpTrigger trigger = (HttpTrigger) method.getParameterAnnotations()[0][0];
        assertEquals(AuthorizationLevel.FUNCTION, trigger.authLevel());
        assertArrayEquals(new HttpMethod[]{HttpMethod.POST}, trigger.methods());
        assertEquals("deployment/verify", trigger.route());
    }

    @Test
    void createsRoleWithObjectIdAndGrantsOnlyRuntimeDml() throws Exception {
        FakeDatabase principalDatabase = new FakeDatabase(null);
        FakeDatabase database = new FakeDatabase(null);
        DatabaseInitializer.initializeRuntimeRole(principalDatabase.connection(), "11111111-1111-1111-1111-111111111111");
        DatabaseInitializer.initializeSchema(database.connection());
        assertEquals("batch_runtime", principalDatabase.roleParameters.get(1));
        assertEquals("11111111-1111-1111-1111-111111111111", principalDatabase.roleParameters.get(2));
        assertEquals(1, principalDatabase.sql.stream().filter(sql -> sql.contains("create_principal_with_oid")).count());
        assertFalse(principalDatabase.sql.stream().anyMatch(sql -> sql.contains("CREATE TABLE") || sql.contains("GRANT")));
        assertFalse(database.sql.stream().anyMatch(sql -> sql.contains("pgaadauth")));
        assertEquals(3, database.sql.stream().filter(sql -> sql.contains("CREATE TABLE IF NOT EXISTS")).count());
        assertTrue(database.sql.contains("SELECT pg_advisory_xact_lock(742193015)"));
        assertTrue(database.sql.contains("REVOKE CREATE ON SCHEMA public FROM PUBLIC"));
        assertTrue(database.sql.contains("REVOKE ALL ON DATABASE workshop FROM PUBLIC"));
        assertTrue(database.sql.contains("GRANT SELECT, INSERT, UPDATE ON TABLE "
                + "batch_page_runs, imported_records, rejected_rows TO batch_runtime"));
        assertFalse(database.sql.stream().anyMatch(sql -> sql.contains("GRANT ALL")));
    }

    @Test
    void repeatedInitializationDoesNotRecreateExistingPrincipal() throws Exception {
        String objectId = "11111111-1111-1111-1111-111111111111";
        FakeDatabase principalDatabase = new FakeDatabase(objectId);
        FakeDatabase database = new FakeDatabase(null);
        DatabaseInitializer.initializeRuntimeRole(principalDatabase.connection(), objectId);
        DatabaseInitializer.initializeSchema(database.connection());
        DatabaseInitializer.initializeRuntimeRole(principalDatabase.connection(), objectId);
        DatabaseInitializer.initializeSchema(database.connection());
        assertFalse(principalDatabase.sql.stream().anyMatch(sql -> sql.contains("create_principal_with_oid")));
        assertEquals(6, database.sql.stream().filter(sql -> sql.contains("CREATE TABLE IF NOT EXISTS")).count());
    }

    @Test
    void roleMappedToAnotherIdentityFailsBeforeSchemaOrGrants() {
        FakeDatabase database = new FakeDatabase("22222222-2222-2222-2222-222222222222");
        assertThrows(SQLException.class, () -> DatabaseInitializer.initializeRuntimeRole(
                database.connection(), "11111111-1111-1111-1111-111111111111"));
        assertFalse(database.sql.stream().anyMatch(sql -> sql.contains("CREATE TABLE") || sql.contains("GRANT")));
    }

    private static final class FakeDatabase {
        final List<String> sql = new ArrayList<>();
        final Map<Integer, String> roleParameters = new HashMap<>();
        private final String existingObjectId;

        FakeDatabase(String existingObjectId) {
            this.existingObjectId = existingObjectId;
        }

        Connection connection() {
            return proxy(Connection.class, (instance, method, arguments) -> {
                switch (method.getName()) {
                    case "createStatement":
                        return proxy(Statement.class, (statement, operation, values) -> {
                            if (operation.getName().equals("close")) {
                                return null;
                            }
                            if (operation.getName().equals("execute")) {
                                sql.add((String) values[0]);
                                return true;
                            }
                            throw new UnsupportedOperationException(operation.getName());
                        });
                    case "prepareStatement":
                        String query = (String) arguments[0];
                        boolean creating = query.contains("create_principal_with_oid");
                        return proxy(PreparedStatement.class, (statement, operation, values) -> {
                            switch (operation.getName()) {
                                case "close":
                                    return null;
                                case "setString":
                                    if (creating) {
                                        roleParameters.put((Integer) values[0], (String) values[1]);
                                    }
                                    return null;
                                case "execute":
                                    sql.add(query);
                                    return true;
                                case "executeQuery":
                                    sql.add(query);
                                    return proxy(ResultSet.class, (result, access, fields) -> {
                                        switch (access.getName()) {
                                            case "next":
                                                return existingObjectId != null;
                                            case "getString":
                                                assertEquals("objectId", fields[0]);
                                                return existingObjectId;
                                            case "close":
                                                return null;
                                            default:
                                                throw new UnsupportedOperationException(access.getName());
                                        }
                                    });
                                default:
                                    throw new UnsupportedOperationException(operation.getName());
                            }
                        });
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
        }
    }
}
