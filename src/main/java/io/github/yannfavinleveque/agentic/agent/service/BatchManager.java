package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.support.HttpHelper;
import io.github.yannfavinleveque.agentic.agent.core.Instance;
import io.github.yannfavinleveque.agentic.agent.core.ProviderConfig;
import io.github.yannfavinleveque.agentic.agent.exception.AgentException;
import io.github.yannfavinleveque.agentic.agent.exception.RequestTimeoutException;
import io.github.yannfavinleveque.agentic.common.Page;
import io.github.yannfavinleveque.agentic.domain.batch.Batch;
import io.github.yannfavinleveque.agentic.domain.batch.BatchRequest;
import io.github.yannfavinleveque.agentic.domain.batch.EndpointType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Manages batch operations for asynchronous processing.
 */
public class BatchManager {

    private static final Logger logger = LoggerFactory.getLogger(BatchManager.class);
    private static final String DEFAULT_BATCH_MODEL = "gpt-4o";

    private final HttpHelper httpHelper;
    private final InstanceRouter instanceRouter;

    public BatchManager(HttpHelper httpHelper, InstanceRouter instanceRouter) {
        this.httpHelper = httpHelper;
        this.instanceRouter = instanceRouter;
    }

    /**
     * Creates a batch request for processing multiple requests asynchronously.
     *
     * @param inputFileId File ID containing batch requests (JSONL format)
     * @param endpoint Endpoint type for the batch
     * @param metadata Optional metadata
     * @return Batch object
     */
    public CompletableFuture<Batch> createBatch(String inputFileId, EndpointType endpoint,
                                                  Map<String, String> metadata) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BatchRequest.BatchRequestBuilder builder = BatchRequest.builder()
                        .inputFileId(inputFileId)
                        .endpoint(endpoint)
                        .completionWindow(BatchRequest.CompletionWindowType.T24H);

                if (metadata != null && !metadata.isEmpty()) {
                    builder.metadata(metadata);
                }

                int instanceIdx = instanceRouter.getNextInstanceForModel(DEFAULT_BATCH_MODEL);
                Instance instance = instanceRouter.getInstance(instanceIdx);

                Batch batch = httpHelper.post(
                        instance, ProviderConfig.Endpoint.BATCHES, null,
                        builder.build(), Batch.class).join();

                logger.info("Created batch: {} with status: {}", batch.getId(), batch.getStatus());
                return batch;

            } catch (Exception e) {
                logger.error("Failed to create batch", e);
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                        "Failed to create batch: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Creates a batch for chat completion requests.
     */
    public CompletableFuture<Batch> createChatCompletionBatch(String inputFileId,
                                                                Map<String, String> metadata) {
        return createBatch(inputFileId, EndpointType.CHAT_COMPLETIONS, metadata);
    }

    /**
     * Gets batch status and details.
     *
     * @param batchId Batch ID
     * @return Batch object
     */
    public CompletableFuture<Batch> getBatch(String batchId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIdx = instanceRouter.getNextInstanceForModel(DEFAULT_BATCH_MODEL);
                Instance instance = instanceRouter.getInstance(instanceIdx);

                Map<String, String> pathParams = new HashMap<>();
                pathParams.put("batchId", batchId);

                return httpHelper.get(instance, ProviderConfig.Endpoint.BATCH, null,
                        Batch.class, pathParams).join();

            } catch (Exception e) {
                logger.error("Failed to get batch: {}", batchId, e);
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                        "Failed to get batch: " + batchId, e);
            }
        });
    }

    /**
     * Cancels an in-progress batch.
     *
     * @param batchId Batch ID
     * @return Batch object showing cancelled status
     */
    public CompletableFuture<Batch> cancelBatch(String batchId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIdx = instanceRouter.getNextInstanceForModel(DEFAULT_BATCH_MODEL);
                Instance instance = instanceRouter.getInstance(instanceIdx);

                Map<String, String> pathParams = new HashMap<>();
                pathParams.put("batchId", batchId);

                Batch batch = httpHelper.post(
                        instance, ProviderConfig.Endpoint.BATCH, null,
                        Map.of("action", "cancel"), Batch.class, pathParams).join();

                logger.info("Cancelled batch: {}", batchId);
                return batch;

            } catch (Exception e) {
                logger.error("Failed to cancel batch: {}", batchId, e);
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                        "Failed to cancel batch: " + batchId, e);
            }
        });
    }

    /**
     * Lists batches.
     *
     * @param limit Maximum number of batches to return
     * @param after Cursor for pagination
     * @return Page of batches
     */
    public CompletableFuture<Page<Batch>> listBatches(Integer limit, String after) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIdx = instanceRouter.getNextInstanceForModel(DEFAULT_BATCH_MODEL);
                Instance instance = instanceRouter.getInstance(instanceIdx);

                return httpHelper.get(instance, ProviderConfig.Endpoint.BATCHES, null,
                        BatchListResponse.class, null).join();

            } catch (Exception e) {
                logger.error("Failed to list batches", e);
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                        "Failed to list batches: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Polls a batch until it reaches a terminal state.
     *
     * @param batchId Batch ID
     * @param pollIntervalSeconds Interval between status checks
     * @param timeoutSeconds Maximum time to wait
     * @return Final batch object
     */
    public CompletableFuture<Batch> pollUntilComplete(String batchId, long pollIntervalSeconds,
                                                        long timeoutSeconds) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                long timeoutMs = timeoutSeconds * 1000;

                int instanceIdx = instanceRouter.getNextInstanceForModel(DEFAULT_BATCH_MODEL);
                Instance instance = instanceRouter.getInstance(instanceIdx);

                Map<String, String> pathParams = new HashMap<>();
                pathParams.put("batchId", batchId);

                while (true) {
                    Batch batch = httpHelper.get(instance, ProviderConfig.Endpoint.BATCH, null,
                            Batch.class, pathParams).join();

                    String status = batch.getStatus();
                    logger.debug("Batch {} status: {}", batchId, status);

                    // Terminal states
                    if ("completed".equals(status) || "failed".equals(status) ||
                        "expired".equals(status) || "cancelled".equals(status)) {
                        logger.info("Batch {} reached terminal state: {}", batchId, status);
                        return batch;
                    }

                    // Check timeout
                    if (System.currentTimeMillis() - startTime > timeoutMs) {
                        throw new RequestTimeoutException(timeoutSeconds);
                    }

                    Thread.sleep(pollIntervalSeconds * 1000);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                        "Batch polling interrupted", e);
            } catch (RequestTimeoutException e) {
                throw e;
            } catch (Exception e) {
                logger.error("Failed to poll batch: {}", batchId, e);
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                        "Failed to poll batch: " + batchId, e);
            }
        });
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class BatchListResponse extends Page<Batch> {
    }
}
