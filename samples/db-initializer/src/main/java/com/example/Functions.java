package com.example;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

public class Functions {
    @FunctionName("VerifyDeployment")
    public HttpResponseMessage verifyDeployment(
            @HttpTrigger(name = "request", methods = HttpMethod.POST,
                    authLevel = AuthorizationLevel.FUNCTION, route = "deployment/verify")
            HttpRequestMessage<Optional<String>> request,
            ExecutionContext context) {
        if (request.getBody().filter(body -> !body.isBlank()).isPresent()) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .body("This endpoint does not accept verification parameters.").build();
        }
        try {
            String hostname = DatabaseInitializer.required(System.getenv(), "WEBSITE_HOSTNAME");
            boolean privateSelf = Arrays.stream(InetAddress.getAllByName(hostname))
                    .allMatch(InetAddress::isSiteLocalAddress);
            InetAddress destination = Arrays.stream(InetAddress.getAllByName("www.microsoft.com"))
                    .filter(address -> address instanceof Inet4Address && !address.isSiteLocalAddress())
                    .findFirst().orElseThrow(() -> new IOException("No public IPv4 probe destination."));
            boolean timedOut = false;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(destination, 443), 3000);
            } catch (SocketTimeoutException expected) {
                timedOut = true;
            }
            boolean verified = privateSelf && timedOut;
            context.getLogger().info("Deployment network verification: privateSelf="
                    + privateSelf + ", internetConnectionTimedOut=" + timedOut);
            return request.createResponseBuilder(verified ? HttpStatus.OK : HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("Content-Type", "application/json")
                    .body(Map.of("privateSelf", privateSelf, "internetConnectionTimedOut", timedOut,
                            "workerRuntime", System.getenv().getOrDefault("FUNCTIONS_WORKER_RUNTIME", "not set"),
                            "javaVersion", System.getProperty("java.version")))
                    .build();
        } catch (IOException | RuntimeException failure) {
            context.getLogger().severe("Deployment verification failed: " + failure.getClass().getSimpleName());
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Deployment verification failed. See initializer logs for diagnostics.").build();
        }
    }

    @FunctionName("InitializeDatabase")
    public HttpResponseMessage initialize(
            @HttpTrigger(name = "request", methods = HttpMethod.POST,
                    authLevel = AuthorizationLevel.FUNCTION, route = "database/initialize")
            HttpRequestMessage<Optional<String>> request,
            ExecutionContext context) {
        if (request.getBody().filter(body -> !body.isBlank()).isPresent()) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .body("This endpoint does not accept initialization parameters.")
                    .build();
        }
        try {
            new DatabaseInitializer().initialize();
            context.getLogger().info("Database initialization and sample input preparation completed.");
            return request.createResponseBuilder(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("{\"initialized\":true,\"schemaVersion\":1}")
                    .build();
        } catch (SQLException failure) {
            context.getLogger().severe("Database initialization failed. SQLSTATE=" + failure.getSQLState());
            String state = failure.getSQLState();
            if (state == null || !state.matches("[0-9A-Z]{5}")) {
                state = "unknown";
            }
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Database initialization failed. SQLSTATE=" + state)
                    .build();
        } catch (IOException | RuntimeException failure) {
            context.getLogger().severe("Database initialization failed: " + failure.getClass().getSimpleName());
        }
        return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Database initialization failed. See initializer logs for diagnostics.")
                .build();
    }
}
