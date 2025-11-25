package io.github.sashirestela.openai.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Simple HTTP helper for making API calls using ProviderConfig.
 * No magic, no interceptors - just straightforward HTTP calls.
 */
public class HttpHelper {

    private static final Logger logger = LoggerFactory.getLogger(HttpHelper.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpHelper() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public HttpHelper(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Makes a POST request to an API endpoint.
     *
     * @param instance The instance to call
     * @param endpoint The endpoint type
     * @param model Model name (for endpoints that need it in path, can be null)
     * @param requestBody Request body object (will be serialized to JSON)
     * @param responseType Response class type
     * @param <T> Response type
     * @return CompletableFuture with parsed response
     */
    public <T> CompletableFuture<T> post(
            Instance instance,
            ProviderConfig.Endpoint endpoint,
            String model,
            Object requestBody,
            Class<T> responseType) {

        return post(instance, endpoint, model, requestBody, responseType, null);
    }

    /**
     * Makes a POST request to an API endpoint with path parameters.
     *
     * @param instance The instance to call
     * @param endpoint The endpoint type
     * @param model Model name (for endpoints that need it in path, can be null)
     * @param requestBody Request body object (will be serialized to JSON)
     * @param responseType Response class type
     * @param pathParams Path parameters to replace (e.g., threadId, runId)
     * @param <T> Response type
     * @return CompletableFuture with parsed response
     */
    public <T> CompletableFuture<T> post(
            Instance instance,
            ProviderConfig.Endpoint endpoint,
            String model,
            Object requestBody,
            Class<T> responseType,
            Map<String, String> pathParams) {

        return CompletableFuture.supplyAsync(() -> {
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

                HttpRequest request = requestBuilder.build();

                // Send request
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                logger.debug("Response {} - Body: {}", response.statusCode(), response.body());

                // Check for errors
                if (response.statusCode() >= 400) {
                    throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
                }

                // Parse response
                return objectMapper.readValue(response.body(), responseType);

            } catch (Exception e) {
                logger.error("HTTP POST failed", e);
                throw new RuntimeException("HTTP POST failed: " + e.getMessage(), e);
            }
        });
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

        return CompletableFuture.supplyAsync(() -> {
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

                // Send request
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                logger.debug("Response {} - Body: {}", response.statusCode(), response.body());

                // Check for errors
                if (response.statusCode() >= 400) {
                    throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
                }

                // Parse response
                return objectMapper.readValue(response.body(), responseType);

            } catch (Exception e) {
                logger.error("HTTP GET failed", e);
                throw new RuntimeException("HTTP GET failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Makes a DELETE request to an API endpoint.
     */
    public CompletableFuture<Void> delete(
            Instance instance,
            ProviderConfig.Endpoint endpoint,
            String model,
            Map<String, String> pathParams) {

        return CompletableFuture.supplyAsync(() -> {
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

                // Send request
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                logger.debug("Response {}", response.statusCode());

                // Check for errors
                if (response.statusCode() >= 400) {
                    throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
                }

                return null;

            } catch (Exception e) {
                logger.error("HTTP DELETE failed", e);
                throw new RuntimeException("HTTP DELETE failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Builds the full URL for an API call.
     */
    private String buildUrl(Instance instance, ProviderConfig.Endpoint endpoint, String model, Map<String, String> pathParams) {
        // Get base URL (remove trailing slash)
        String baseUrl = instance.getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        // Get path from ProviderConfig
        String path = ProviderConfig.getPath(instance.getProvider(), endpoint, model);

        // Replace path parameters if any
        if (pathParams != null && !pathParams.isEmpty()) {
            path = ProviderConfig.replacePathParams(path, pathParams);
        }

        // Build full URL
        String url = baseUrl + path;

        // Add query parameters
        Map<String, String> queryParams = ProviderConfig.getQueryParams(instance.getProvider(), instance.getAzureApiVersion());
        if (!queryParams.isEmpty()) {
            StringBuilder sb = new StringBuilder(url);
            sb.append("?");
            boolean first = true;
            for (Map.Entry<String, String> param : queryParams.entrySet()) {
                if (!first) sb.append("&");
                sb.append(param.getKey()).append("=").append(param.getValue());
                first = false;
            }
            url = sb.toString();
        }

        return url;
    }

    /**
     * Adds authentication headers based on the provider.
     */
    private void addAuthHeaders(HttpRequest.Builder requestBuilder, Instance instance) {
        Map<String, String> headers = ProviderConfig.getHeaders(
                instance.getProvider(),
                instance.getApiKey(),
                instance.getAzureApiVersion()
        );
        for (Map.Entry<String, String> header : headers.entrySet()) {
            requestBuilder.header(header.getKey(), header.getValue());
        }
    }

    /**
     * Checks if an endpoint is part of the Assistants API (requires beta header).
     */
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

    /**
     * Gets the ObjectMapper for external use (e.g., custom serialization).
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    // ==================== MULTIPART FORM DATA ====================

    /**
     * Makes a POST request with multipart/form-data (for file uploads).
     *
     * @param instance The instance to call
     * @param endpoint The endpoint type
     * @param filePath Path to the file to upload
     * @param formFields Additional form fields (e.g., "purpose" -> "assistants")
     * @param responseType Response class type
     * @param <T> Response type
     * @return CompletableFuture with parsed response
     */
    public <T> CompletableFuture<T> postMultipart(
            Instance instance,
            ProviderConfig.Endpoint endpoint,
            Path filePath,
            Map<String, String> formFields,
            Class<T> responseType) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Build URL
                String url = buildUrl(instance, endpoint, null, null);

                // Generate boundary
                String boundary = new BigInteger(256, new SecureRandom()).toString();

                // Build multipart body
                byte[] multipartBody = buildMultipartBody(boundary, filePath, formFields);

                logger.debug("POST MULTIPART {} - File: {}, Fields: {}", url, filePath.getFileName(), formFields);

                // Build request
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                        .timeout(Duration.ofSeconds(300)); // Longer timeout for file uploads

                // Add auth headers
                addAuthHeaders(requestBuilder, instance);

                HttpRequest request = requestBuilder.build();

                // Send request
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                logger.debug("Response {} - Body: {}", response.statusCode(), response.body());

                // Check for errors
                if (response.statusCode() >= 400) {
                    throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
                }

                // Parse response
                return objectMapper.readValue(response.body(), responseType);

            } catch (Exception e) {
                logger.error("HTTP POST MULTIPART failed", e);
                throw new RuntimeException("HTTP POST MULTIPART failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Builds multipart/form-data body for file upload.
     */
    private byte[] buildMultipartBody(String boundary, Path filePath, Map<String, String> formFields) throws IOException {
        List<byte[]> parts = new ArrayList<>();
        String CRLF = "\r\n";

        // Add form fields
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

        // Add file
        String filename = filePath.getFileName().toString();
        String contentType = Files.probeContentType(filePath);
        if (contentType == null) {
            contentType = "application/octet-stream";
        }

        StringBuilder fileHeader = new StringBuilder();
        fileHeader.append("--").append(boundary).append(CRLF);
        fileHeader.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(filename).append("\"").append(CRLF);
        fileHeader.append("Content-Type: ").append(contentType).append(CRLF);
        fileHeader.append(CRLF);
        parts.add(fileHeader.toString().getBytes(StandardCharsets.UTF_8));

        // Add file content
        parts.add(Files.readAllBytes(filePath));
        parts.add(CRLF.getBytes(StandardCharsets.UTF_8));

        // Add closing boundary
        parts.add(("--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8));

        // Combine all parts
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
