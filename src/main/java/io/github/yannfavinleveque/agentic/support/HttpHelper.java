package io.github.yannfavinleveque.agentic.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yannfavinleveque.agentic.agent.core.Instance;
import io.github.yannfavinleveque.agentic.agent.core.ProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Simple HTTP helper for making API calls using ProviderConfig. NOTE: Concurrency control has been
 * moved to AgentRequestService level. HttpHelper now executes requests directly without permit
 * management. This ensures one permit = one agent request (REQUEST START to REQUEST END), not one
 * permit per HTTP call (which caused issues with multi-step OpenAI flows).
 */
public class HttpHelper {

    private static final Logger logger = LoggerFactory.getLogger(HttpHelper.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Creates HttpHelper.
     *
     * @param maxStreamsPerInstance Ignored - kept for API compatibility. Concurrency is now managed at
     *                              AgentRequestService level.
     */
    public HttpHelper(int maxStreamsPerInstance) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_2)
                .build();
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        logger.info("HttpHelper initialized (concurrency control at AgentRequestService level)");
    }

    /**
     * Creates HttpHelper with custom HttpClient and ObjectMapper (primarily for testing).
     */
    public HttpHelper(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    // ==================== HTTP METHODS ====================

    /**
     * Makes a POST request to an API endpoint.
     */
    public <T> CompletableFuture<T> post(
            Instance instance,
            ProviderConfig.Endpoint endpoint,
            String model,
            Object requestBody,
            Class<T> responseType) {
        return post(instance, endpoint, model, requestBody, responseType, null);
    }

    public <T> CompletableFuture<T> post(
            Instance instance,
            ProviderConfig.Endpoint endpoint,
            String model,
            Object requestBody,
            Class<T> responseType,
            Map<String, String> pathParams) {
        return post(instance, endpoint, model, requestBody, responseType, pathParams, null);
    }

    public <T> CompletableFuture<T> post(
            Instance instance,
            ProviderConfig.Endpoint endpoint,
            String model,
            Object requestBody,
            Class<T> responseType,
            Map<String, String> pathParams,
            Map<String, String> extraHeaders) {

        try {
            // Build URL
            String url = buildUrl(instance, endpoint, model, pathParams);

            // Serialize body
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            logger.debug("POST {} - Body: {}", url, jsonBody);

            // Build request
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(120));

            // Add auth headers
            addAuthHeaders(requestBuilder, instance);

            // Add beta header for assistants API
            if (isAssistantsEndpoint(endpoint)) {
                requestBuilder.header("OpenAI-Beta", "assistants=v2");
            }

            // Add extra headers if provided
            if (extraHeaders != null) {
                for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                    requestBuilder.header(header.getKey(), header.getValue());
                }
            }

            HttpRequest request = requestBuilder.build();

            // Send request asynchronously
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        logger.debug("Response {} - Body: {}", response.statusCode(), response.body());

                        if (response.statusCode() >= 400) {
                            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
                        }

                        try {
                            return objectMapper.readValue(response.body(), responseType);
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException("Failed to parse response: " + e.getMessage(), e);
                        }
                    });

        } catch (Exception e) {
            logger.error("HTTP POST failed", e);
            return CompletableFuture.failedFuture(new RuntimeException("HTTP POST failed: " + e.getMessage(), e));
        }
    }

    /**
     * Makes a POST request and returns the raw JSON response string. Useful when you need to manually
     * parse the response.
     */
    public CompletableFuture<String> postRaw(
            Instance instance,
            ProviderConfig.Endpoint endpoint,
            String model,
            Object requestBody) {

        try {
            // Build URL
            String url = buildUrl(instance, endpoint, model, null);

            // Serialize body
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            logger.debug("POST {} - Body: {}", url, jsonBody);

            // Build request
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(120));

            // Add auth headers
            addAuthHeaders(requestBuilder, instance);

            HttpRequest request = requestBuilder.build();

            // Send request asynchronously
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        logger.debug("Response {} - Body: {}", response.statusCode(), response.body());

                        if (response.statusCode() >= 400) {
                            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
                        }

                        return response.body();
                    });

        } catch (Exception e) {
            logger.error("HTTP POST (raw) failed", e);
            return CompletableFuture.failedFuture(new RuntimeException("HTTP POST failed: " + e.getMessage(), e));
        }
    }

    /**
     * Makes a GET request to an API endpoint.
     */
    public <T> CompletableFuture<T> get(
            Instance instance,
            ProviderConfig.Endpoint endpoint,
            String model,
            Class<T> responseType,
            Map<String, String> pathParams) {
        try {
            // Build URL
            String url = buildUrl(instance, endpoint, model, pathParams);

            logger.debug("GET {}", url);

            // Build request
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(60));

            // Add auth headers
            addAuthHeaders(requestBuilder, instance);

            // Add beta header for assistants API
            if (isAssistantsEndpoint(endpoint)) {
                requestBuilder.header("OpenAI-Beta", "assistants=v2");
            }

            HttpRequest request = requestBuilder.build();

            // Send request asynchronously
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        logger.debug("Response {} - Body: {}", response.statusCode(), response.body());

                        if (response.statusCode() >= 400) {
                            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
                        }

                        try {
                            return objectMapper.readValue(response.body(), responseType);
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException("Failed to parse response: " + e.getMessage(), e);
                        }
                    });

        } catch (Exception e) {
            logger.error("HTTP GET failed", e);
            return CompletableFuture.failedFuture(new RuntimeException("HTTP GET failed: " + e.getMessage(), e));
        }
    }

    /**
     * Makes a DELETE request to an API endpoint.
     */
    public CompletableFuture<Void> delete(
            Instance instance,
            ProviderConfig.Endpoint endpoint,
            String model,
            Map<String, String> pathParams) {
        try {
            // Build URL
            String url = buildUrl(instance, endpoint, model, pathParams);

            logger.debug("DELETE {}", url);

            // Build request
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .DELETE()
                    .timeout(Duration.ofSeconds(60));

            // Add auth headers
            addAuthHeaders(requestBuilder, instance);

            // Add beta header for assistants API
            if (isAssistantsEndpoint(endpoint)) {
                requestBuilder.header("OpenAI-Beta", "assistants=v2");
            }

            HttpRequest request = requestBuilder.build();

            // Send request asynchronously
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        logger.debug("Response {}", response.statusCode());

                        if (response.statusCode() >= 400) {
                            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
                        }

                        return null;
                    });

        } catch (Exception e) {
            logger.error("HTTP DELETE failed", e);
            return CompletableFuture.failedFuture(new RuntimeException("HTTP DELETE failed: " + e.getMessage(), e));
        }
    }

    // ==================== MULTIPART ====================

    public <T> CompletableFuture<T> postMultipartBase64(
            Instance instance,
            ProviderConfig.Endpoint endpoint,
            String imageBase64,
            Map<String, String> formFields,
            Class<T> responseType) {
        try {
            String url = buildUrl(instance, endpoint, null, null);
            String boundary = new BigInteger(256, new SecureRandom()).toString();
            byte[] multipartBody = buildMultipartBodyBase64(boundary, imageBase64, formFields);

            logger.debug("POST MULTIPART BASE64 {} - ImageSize: {} bytes, Fields: {}",
                    url, imageBase64 != null ? imageBase64.length() : 0, formFields);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                    .timeout(Duration.ofSeconds(300));

            addAuthHeaders(requestBuilder, instance);

            HttpRequest request = requestBuilder.build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        logger.debug("Response {} - Body: {}", response.statusCode(), response.body());

                        if (response.statusCode() >= 400) {
                            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
                        }

                        try {
                            return objectMapper.readValue(response.body(), responseType);
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException("Failed to parse response: " + e.getMessage(), e);
                        }
                    });

        } catch (Exception e) {
            logger.error("HTTP POST MULTIPART BASE64 failed", e);
            return CompletableFuture
                    .failedFuture(new RuntimeException("HTTP POST MULTIPART BASE64 failed: " + e.getMessage(), e));
        }
    }

    public <T> CompletableFuture<T> postMultipart(
            Instance instance,
            ProviderConfig.Endpoint endpoint,
            Path filePath,
            Map<String, String> formFields,
            Class<T> responseType) {
        try {
            String url = buildUrl(instance, endpoint, null, null);
            String boundary = new BigInteger(256, new SecureRandom()).toString();
            byte[] multipartBody = buildMultipartBody(boundary, filePath, formFields);

            logger.debug("POST MULTIPART {} - File: {}, Fields: {}", url, filePath.getFileName(), formFields);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                    .timeout(Duration.ofSeconds(300));

            addAuthHeaders(requestBuilder, instance);

            HttpRequest request = requestBuilder.build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        logger.debug("Response {} - Body: {}", response.statusCode(), response.body());

                        if (response.statusCode() >= 400) {
                            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
                        }

                        try {
                            return objectMapper.readValue(response.body(), responseType);
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException("Failed to parse response: " + e.getMessage(), e);
                        }
                    });

        } catch (Exception e) {
            logger.error("HTTP POST MULTIPART failed", e);
            return CompletableFuture
                    .failedFuture(new RuntimeException("HTTP POST MULTIPART failed: " + e.getMessage(), e));
        }
    }

    // ==================== HELPERS ====================

    private String buildUrl(Instance instance, ProviderConfig.Endpoint endpoint, String model,
            Map<String, String> pathParams) {
        String baseUrl = instance.getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String path = ProviderConfig.getPath(instance.getProvider(), endpoint, model);

        if (pathParams != null && !pathParams.isEmpty()) {
            path = ProviderConfig.replacePathParams(path, pathParams);
        }

        String url = baseUrl + path;

        Map<String, String> queryParams = ProviderConfig.getQueryParams(instance.getProvider(),
                instance.getAzureApiVersion(), endpoint);
        if (!queryParams.isEmpty()) {
            StringBuilder sb = new StringBuilder(url);
            sb.append("?");
            boolean first = true;
            for (Map.Entry<String, String> param : queryParams.entrySet()) {
                if (!first)
                    sb.append("&");
                sb.append(param.getKey()).append("=").append(param.getValue());
                first = false;
            }
            url = sb.toString();
        }

        return url;
    }

    private void addAuthHeaders(HttpRequest.Builder requestBuilder, Instance instance) {
        Map<String, String> headers = ProviderConfig.getHeaders(
                instance.getProvider(),
                instance.getApiKey(),
                instance.getAzureApiVersion());
        for (Map.Entry<String, String> header : headers.entrySet()) {
            requestBuilder.header(header.getKey(), header.getValue());
        }
    }

    private boolean isAssistantsEndpoint(ProviderConfig.Endpoint endpoint) {
        return endpoint == ProviderConfig.Endpoint.ASSISTANTS ||
                endpoint == ProviderConfig.Endpoint.ASSISTANT ||
                endpoint == ProviderConfig.Endpoint.THREADS ||
                endpoint == ProviderConfig.Endpoint.THREAD ||
                endpoint == ProviderConfig.Endpoint.THREAD_MESSAGES ||
                endpoint == ProviderConfig.Endpoint.THREAD_MESSAGE ||
                endpoint == ProviderConfig.Endpoint.THREAD_RUNS ||
                endpoint == ProviderConfig.Endpoint.THREAD_RUN ||
                endpoint == ProviderConfig.Endpoint.THREAD_RUN_STEPS ||
                endpoint == ProviderConfig.Endpoint.VECTOR_STORES ||
                endpoint == ProviderConfig.Endpoint.VECTOR_STORE ||
                endpoint == ProviderConfig.Endpoint.VECTOR_STORE_FILES;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    private byte[] buildMultipartBodyBase64(String boundary, String imageBase64, Map<String, String> formFields)
            throws IOException {
        List<byte[]> parts = new ArrayList<>();
        String CRLF = "\r\n";

        if (formFields != null) {
            for (Map.Entry<String, String> field : formFields.entrySet()) {
                StringBuilder sb = new StringBuilder();
                sb.append("--").append(boundary).append(CRLF);
                sb.append("Content-Disposition: form-data; name=\"").append(field.getKey()).append("\"").append(CRLF);
                sb.append(CRLF);
                sb.append(field.getValue()).append(CRLF);
                parts.add(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        }

        StringBuilder fileHeader = new StringBuilder();
        fileHeader.append("--").append(boundary).append(CRLF);
        fileHeader.append("Content-Disposition: form-data; name=\"image\"; filename=\"image.png\"").append(CRLF);
        fileHeader.append("Content-Type: image/png").append(CRLF);
        fileHeader.append(CRLF);
        parts.add(fileHeader.toString().getBytes(StandardCharsets.UTF_8));

        byte[] imageBytes = java.util.Base64.getDecoder().decode(imageBase64);
        parts.add(imageBytes);
        parts.add(CRLF.getBytes(StandardCharsets.UTF_8));

        parts.add(("--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8));

        int totalLength = parts.stream().mapToInt(p -> p.length).sum();
        byte[] result = new byte[totalLength];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }

        return result;
    }

    private byte[] buildMultipartBody(String boundary, Path filePath, Map<String, String> formFields)
            throws IOException {
        List<byte[]> parts = new ArrayList<>();
        String CRLF = "\r\n";

        if (formFields != null) {
            for (Map.Entry<String, String> field : formFields.entrySet()) {
                StringBuilder sb = new StringBuilder();
                sb.append("--").append(boundary).append(CRLF);
                sb.append("Content-Disposition: form-data; name=\"").append(field.getKey()).append("\"").append(CRLF);
                sb.append(CRLF);
                sb.append(field.getValue()).append(CRLF);
                parts.add(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        }

        String filename = filePath.getFileName().toString();
        String contentType = Files.probeContentType(filePath);
        if (contentType == null) {
            contentType = "application/octet-stream";
        }

        StringBuilder fileHeader = new StringBuilder();
        fileHeader.append("--").append(boundary).append(CRLF);
        fileHeader.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(filename)
                .append("\"")
                .append(CRLF);
        fileHeader.append("Content-Type: ").append(contentType).append(CRLF);
        fileHeader.append(CRLF);
        parts.add(fileHeader.toString().getBytes(StandardCharsets.UTF_8));

        parts.add(Files.readAllBytes(filePath));
        parts.add(CRLF.getBytes(StandardCharsets.UTF_8));

        parts.add(("--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8));

        int totalLength = parts.stream().mapToInt(p -> p.length).sum();
        byte[] result = new byte[totalLength];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }

        return result;
    }

}
