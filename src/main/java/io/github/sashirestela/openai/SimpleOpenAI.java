package io.github.sashirestela.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sashirestela.cleverclient.http.HttpRequestData;
import io.github.sashirestela.cleverclient.support.ContentType;
import io.github.sashirestela.openai.OpenAIRealtime.BaseRealtimeConfig;
import io.github.sashirestela.openai.support.Constant;
import io.github.sashirestela.slimvalidator.constraints.Required;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.SuperBuilder;

import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Unified OpenAI implementation supporting both standard OpenAI and Azure OpenAI.
 * Automatically handles URL construction and headers based on provider type.
 */
public class SimpleOpenAI extends BaseSimpleOpenAI {

    /**
     * Constructor used to generate a builder.
     *
     * @param apiKey         Identifier to be used for authentication. Mandatory.
     * @param organizationId Organization's id to be charged for usage (OpenAI only). Optional.
     * @param projectId      Project's id to provide access to a single project (OpenAI only). Optional.
     * @param baseUrl        Host's url. For OpenAI, defaults to 'https://api.openai.com'. For Azure, provide resource URL. Optional for OpenAI, Mandatory for Azure.
     * @param httpClient     A {@link java.net.http.HttpClient HttpClient} object. One is created by default if not provided. Optional.
     * @param objectMapper   Provides Json conversions either to and from objects. Optional.
     * @param realtimeConfig Configuration for websocket Realtime API (OpenAI only). Optional.
     * @param isAzure        Whether this is an Azure OpenAI instance. Defaults to false.
     * @param azureApiVersion Azure OpenAI API version (e.g., "2024-08-01-preview"). Required if isAzure=true.
     */
    @Builder
    public SimpleOpenAI(@NonNull String apiKey,
                       String organizationId,
                       String projectId,
                       String baseUrl,
                       HttpClient httpClient,
                       ObjectMapper objectMapper,
                       RealtimeConfig realtimeConfig,
                       Boolean isAzure,
                       String azureApiVersion) {
        super(prepareBaseSimpleOpenAIArgs(apiKey, organizationId, projectId, baseUrl, httpClient, objectMapper,
                realtimeConfig, isAzure != null && isAzure, azureApiVersion));
    }

    /**
     * Prepares configuration arguments for both OpenAI and Azure OpenAI.
     */
    public static BaseSimpleOpenAIArgs prepareBaseSimpleOpenAIArgs(
            String apiKey,
            String organizationId,
            String projectId,
            String baseUrl,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            RealtimeConfig realtimeConfig,
            boolean isAzure,
            String azureApiVersion) {

        // Setup headers based on provider
        var headers = new HashMap<String, String>();

        if (isAzure) {
            // Azure uses api-key header
            headers.put(Constant.AZURE_APIKEY_HEADER, apiKey);
        } else {
            // OpenAI uses Authorization: Bearer
            headers.put(Constant.AUTHORIZATION_HEADER, Constant.BEARER_AUTHORIZATION + apiKey);
            if (organizationId != null) {
                headers.put(Constant.OPENAI_ORG_HEADER, organizationId);
            }
            if (projectId != null) {
                headers.put(Constant.OPENAI_PRJ_HEADER, projectId);
            }
        }

        // Setup realtime config (OpenAI only)
        BaseRealtimeConfig baseRealtimeConfig = null;
        if (!isAzure && realtimeConfig != null) {
            var realtimeHeaders = new HashMap<String, String>();
            realtimeHeaders.put(Constant.AUTHORIZATION_HEADER, Constant.BEARER_AUTHORIZATION + apiKey);
            realtimeHeaders.put(Constant.OPENAI_BETA_HEADER, Constant.OPENAI_REALTIME_VERSION);
            var realtimeQueryParams = new HashMap<String, String>();
            realtimeQueryParams.put(Constant.OPENAI_REALTIME_MODEL_NAME, realtimeConfig.getModel());
            baseRealtimeConfig = BaseRealtimeConfig.builder()
                    .endpointUrl(Optional.ofNullable(realtimeConfig.getEndpointUrl())
                            .orElse(Constant.OPENAI_WS_ENDPOINT_URL))
                    .headers(realtimeHeaders)
                    .queryParams(realtimeQueryParams)
                    .build();
        }

        // Setup request interceptor for Azure
        UnaryOperator<HttpRequestData> requestInterceptor = null;
        if (isAzure) {
            final String finalAzureApiVersion = azureApiVersion;
            requestInterceptor = request -> {
                var url = request.getUrl();
                var contentType = request.getContentType();

                // Modify body if needed
                if (contentType != null && request.getBody() != null) {
                    var body = modifyBodyForAzure(request, contentType, url);
                    request.setBody(body);
                }

                // Modify URL for Azure
                url = buildAzureUrl(url, finalAzureApiVersion);
                request.setUrl(url);

                return request;
            };
        }

        // Build base arguments
        return BaseSimpleOpenAIArgs.builder()
                .baseUrl(isAzure ? baseUrl : Optional.ofNullable(baseUrl).orElse(Constant.OPENAI_BASE_URL))
                .headers(headers)
                .httpClient(httpClient)
                .requestInterceptor(requestInterceptor)
                .objectMapper(objectMapper)
                .baseRealtimeConfig(baseRealtimeConfig)
                .build();
    }

    // ==================== AZURE HELPER METHODS ====================

    /**
     * Extracts deployment name from Azure URL.
     * Example: "/deployments/gpt-4o/chat/completions" -> "gpt-4o"
     */
    private static String extractDeployment(String url) {
        final String DEPLOYMENT_REGEX = "/deployments/([^/]+)/";
        var pattern = Pattern.compile(DEPLOYMENT_REGEX);
        var matcher = pattern.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Builds Azure-compliant URL from OpenAI-style URL.
     * - Adds api-version query parameter
     * - Removes /v1 from path
     * - Keeps deployment in path for chat/images endpoints
     */
    private static String buildAzureUrl(String url, String apiVersion) {
        final String VERSION_REGEX = "(/v\\d+\\.*\\d*)";
        final String DEPLOYMENT_REGEX = "/deployments/[^/]+/";
        final String CHAT_COMPLETIONS_LITERAL = "/chat/completions";
        final String IMAGES_GENERATIONS_LITERAL = "/images/generations";

        // Add api-version parameter
        url += (url.contains("?") ? "&" : "?") + Constant.AZURE_API_VERSION + "=" + apiVersion;

        // Remove version path (e.g., /v1) - Azure baseUrls already contain /openai
        url = url.replaceFirst(VERSION_REGEX, "");

        // Keep deployment in URL for /chat/completions, /images/generations, and /embeddings calls
        final String EMBEDDINGS_LITERAL = "/embeddings";
        if (!url.contains(CHAT_COMPLETIONS_LITERAL) &&
            !url.contains(IMAGES_GENERATIONS_LITERAL) &&
            !url.contains(EMBEDDINGS_LITERAL)) {
            url = url.replaceFirst(DEPLOYMENT_REGEX, "/");
        }

        return url;
    }

    /**
     * Modifies request body for Azure compatibility.
     * - For assistants: adds deployment as model
     * - For other endpoints: removes model field (it's in URL)
     */
    @SuppressWarnings("unchecked")
    private static Object modifyBodyForAzure(HttpRequestData request, ContentType contentType, String url) {
        var deployment = extractDeployment(url);
        var body = request.getBody();

        if (contentType.equals(ContentType.APPLICATION_JSON)) {
            return modifyJsonBody(url, (String) body, deployment);
        } else {
            return modifyMapBody(url, (Map<String, Object>) body, deployment);
        }
    }

    /**
     * Modifies JSON string body for Azure.
     */
    private static Object modifyJsonBody(String url, String body, String deployment) {
        final String MODEL_ENTRY_REGEX = "\"model\"\\s*:\\s*\"[^\"]+\"\\s*,?\\s*";
        final String TRAILING_COMMA_REGEX = ",\\s*}";
        final String CLOSING_BRACE = "}";
        final String MODEL_LITERAL = "model";
        final String ASSISTANTS_LITERAL = "/assistants";

        // Handle null body (e.g., GET requests, retrieve operations)
        if (body == null) {
            return null;
        }

        var model = "";
        if (url.contains(ASSISTANTS_LITERAL)) {
            model = "\"" + MODEL_LITERAL + "\":\"" + deployment + "\",";
        }
        body = body.replaceFirst(MODEL_ENTRY_REGEX, model);
        body = body.replaceFirst(TRAILING_COMMA_REGEX, CLOSING_BRACE);

        return body;
    }

    /**
     * Modifies Map body for Azure.
     */
    private static Object modifyMapBody(String url, Map<String, Object> body, String deployment) {
        final String ASSISTANTS_LITERAL = "/assistants";
        final String MODEL_LITERAL = "model";

        if (url.contains(ASSISTANTS_LITERAL)) {
            body.put(MODEL_LITERAL, deployment);
        } else {
            body.remove(MODEL_LITERAL);
        }
        return body;
    }

    // ==================== SERVICE METHODS ====================

    @Override
    public OpenAI.Audios audios() {
        if (audioService == null) {
            audioService = cleverClient.create(OpenAI.Audios.class);
        }
        return audioService;
    }

    @Override
    public OpenAI.Batches batches() {
        if (batchService == null) {
            batchService = cleverClient.create(OpenAI.Batches.class);
        }
        return batchService;
    }

    @Override
    public OpenAI.Completions completions() {
        if (completionService == null) {
            completionService = cleverClient.create(OpenAI.Completions.class);
        }
        return completionService;
    }

    @Override
    public OpenAI.Embeddings embeddings() {
        if (embeddingService == null) {
            embeddingService = cleverClient.create(OpenAI.Embeddings.class);
        }
        return embeddingService;
    }

    @Override
    public OpenAI.FineTunings fineTunings() {
        if (fineTuningService == null) {
            fineTuningService = cleverClient.create(OpenAI.FineTunings.class);
        }
        return fineTuningService;
    }

    @Override
    public OpenAI.Images images() {
        if (imageService == null) {
            imageService = cleverClient.create(OpenAI.Images.class);
        }
        return imageService;
    }

    @Override
    public OpenAI.Models models() {
        if (modelService == null) {
            modelService = cleverClient.create(OpenAI.Models.class);
        }
        return modelService;
    }

    @Override
    public OpenAI.Moderations moderations() {
        if (moderationService == null) {
            moderationService = cleverClient.create(OpenAI.Moderations.class);
        }
        return moderationService;
    }

    @Override
    public OpenAI.Uploads uploads() {
        if (uploadService == null) {
            uploadService = cleverClient.create(OpenAI.Uploads.class);
        }
        return uploadService;
    }

    @Override
    public OpenAIBeta2.Assistants assistants() {
        if (assistantService == null) {
            assistantService = cleverClient.create(OpenAIBeta2.Assistants.class);
        }
        return assistantService;
    }

    @Override
    public OpenAIBeta2.Threads threads() {
        if (threadService == null) {
            threadService = cleverClient.create(OpenAIBeta2.Threads.class);
        }
        return threadService;
    }

    @Override
    public OpenAIBeta2.ThreadMessages threadMessages() {
        if (threadMessageService == null) {
            threadMessageService = cleverClient.create(OpenAIBeta2.ThreadMessages.class);
        }
        return threadMessageService;
    }

    @Override
    public OpenAIBeta2.ThreadRuns threadRuns() {
        if (threadRunService == null) {
            threadRunService = cleverClient.create(OpenAIBeta2.ThreadRuns.class);
        }
        return threadRunService;
    }

    @Override
    public OpenAIBeta2.ThreadRunSteps threadRunSteps() {
        if (threadRunStepService == null) {
            threadRunStepService = cleverClient.create(OpenAIBeta2.ThreadRunSteps.class);
        }
        return threadRunStepService;
    }

    @Override
    public OpenAIBeta2.VectorStores vectorStores() {
        if (vectorStoreService == null) {
            vectorStoreService = cleverClient.create(OpenAIBeta2.VectorStores.class);
        }
        return vectorStoreService;
    }

    @Override
    public OpenAIBeta2.VectorStoreFiles vectorStoreFiles() {
        if (vectorStoreFileService == null) {
            vectorStoreFileService = cleverClient.create(OpenAIBeta2.VectorStoreFiles.class);
        }
        return vectorStoreFileService;
    }

    @Override
    public OpenAIBeta2.VectorStoreFileBatches vectorStoreFileBatches() {
        if (vectorStoreFileBatchService == null) {
            vectorStoreFileBatchService = cleverClient.create(OpenAIBeta2.VectorStoreFileBatches.class);
        }
        return vectorStoreFileBatchService;
    }

    @Override
    public OpenAIRealtime realtime() {
        return this.realtime;
    }

    @Getter
    @SuperBuilder
    public static class RealtimeConfig extends BaseRealtimeConfig {

        @Required
        private String model;

        public static RealtimeConfig of(String model) {
            return RealtimeConfig.builder().model(model).build();
        }

    }

}
