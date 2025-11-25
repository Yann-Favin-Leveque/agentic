package io.github.sashirestela.openai.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
               endpoint == ProviderConfig.Endpoint.THREADS ||
               endpoint == ProviderConfig.Endpoint.THREAD_MESSAGES ||
               endpoint == ProviderConfig.Endpoint.THREAD_RUNS ||
               endpoint == ProviderConfig.Endpoint.THREAD_RUN_STEPS ||
               endpoint == ProviderConfig.Endpoint.VECTOR_STORES ||
               endpoint == ProviderConfig.Endpoint.VECTOR_STORE_FILES;
    }

    /**
     * Gets the ObjectMapper for external use (e.g., custom serialization).
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
