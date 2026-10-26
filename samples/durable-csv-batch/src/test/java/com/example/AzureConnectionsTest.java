package com.example;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AzureConnectionsTest {
    @Test
    void preservesLocalAuthenticationWhenNoModeIsConfigured() {
        assertFalse(AzureConnections.usesManagedIdentity(Map.of(), "DB_AUTHENTICATION"));
    }

    @Test
    void selectsManagedIdentityExplicitly() {
        assertTrue(AzureConnections.usesManagedIdentity(
                Map.of("DB_AUTHENTICATION", "managedidentity"), "DB_AUTHENTICATION"));
    }

    @Test
    void rejectsUnknownModeInsteadOfFallingBackToPassword() {
        assertThrows(IllegalStateException.class, () -> AzureConnections.usesManagedIdentity(
                Map.of("DB_AUTHENTICATION", "managed-identty"), "DB_AUTHENTICATION"));
    }

    @Test
    void missingOrBlankSettingsFailExplicitly() {
        assertThrows(IllegalStateException.class, () -> AzureConnections.required(Map.of(), "DB_USERNAME"));
        assertThrows(IllegalStateException.class, () -> AzureConnections.required(
                Map.of("DB_USERNAME", " "), "DB_USERNAME"));
    }
}
