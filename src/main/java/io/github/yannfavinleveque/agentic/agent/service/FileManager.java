package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.support.HttpHelper;
import io.github.yannfavinleveque.agentic.agent.core.Instance;
import io.github.yannfavinleveque.agentic.agent.core.ProviderConfig;
import io.github.yannfavinleveque.agentic.agent.exception.AgentException;
import io.github.yannfavinleveque.agentic.domain.assistant.VectorStore;
import io.github.yannfavinleveque.agentic.domain.assistant.VectorStoreRequest;
import io.github.yannfavinleveque.agentic.domain.file.FileResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Manages file and vector store operations.
 */
public class FileManager {

    private static final Logger logger = LoggerFactory.getLogger(FileManager.class);

    private final HttpHelper httpHelper;
    private final InstanceRouter instanceRouter;

    public FileManager(HttpHelper httpHelper, InstanceRouter instanceRouter) {
        this.httpHelper = httpHelper;
        this.instanceRouter = instanceRouter;
    }

    // ==================== FILE OPERATIONS ====================

    /**
     * Uploads a file for the specified purpose.
     *
     * @param filePath Path to the file
     * @param purpose Purpose (e.g., "assistants", "fine-tune", "batch")
     * @return Encoded file reference
     */
    public CompletableFuture<String> uploadFile(Path filePath, String purpose) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIdx = instanceRouter.getNextGlobalInstance();
                Instance instance = instanceRouter.getInstance(instanceIdx);

                logger.debug("Uploading file {} to instance {} with purpose: {}",
                        filePath.getFileName(), instanceIdx, purpose);

                Map<String, String> formFields = new HashMap<>();
                formFields.put("purpose", purpose);

                FileResponse response = httpHelper.postMultipart(
                        instance, ProviderConfig.Endpoint.FILES,
                        filePath, formFields,
                        FileResponse.class).join();

                String fileId = response.getId();
                logger.info("File uploaded: {} -> {} on instance {}",
                        filePath.getFileName(), fileId, instanceIdx);

                return instanceRouter.encodeWithInstance(instanceIdx, fileId);

            } catch (Exception e) {
                logger.error("Failed to upload file: {}", filePath, e);
                throw new AgentException(AgentException.ErrorCode.FILE_UPLOAD_FAILED,
                        "File upload failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Uploads a file for use with assistants.
     */
    public CompletableFuture<String> uploadFileForAssistants(Path filePath) {
        return uploadFile(filePath, "assistants");
    }

    /**
     * Deletes a file.
     *
     * @param fileRef Encoded file reference
     * @return true if deleted successfully
     */
    public CompletableFuture<Boolean> deleteFile(String fileRef) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIndex = instanceRouter.extractInstanceIndex(fileRef);
                String actualFileId = instanceRouter.extractActualId(fileRef);
                Instance instance = instanceRouter.getInstance(instanceIndex);

                Map<String, String> pathParams = new HashMap<>();
                pathParams.put("fileId", actualFileId);

                httpHelper.delete(instance, ProviderConfig.Endpoint.FILE, null, pathParams).join();
                logger.info("File deleted: {} from instance {}", actualFileId, instanceIndex);
                return true;

            } catch (Exception e) {
                logger.error("Failed to delete file: {}", fileRef, e);
                return false;
            }
        });
    }

    // ==================== VECTOR STORE OPERATIONS ====================

    /**
     * Creates a vector store with file attachments.
     *
     * @param name Vector store name
     * @param fileIds List of file references
     * @return Encoded vector store reference
     */
    public CompletableFuture<String> createVectorStore(String name, List<String> fileIds) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int instIndex;
                List<String> actualFileIds;

                if (fileIds != null && !fileIds.isEmpty()) {
                    // Use instance from first file reference
                    instIndex = instanceRouter.extractInstanceIndex(fileIds.get(0));
                    actualFileIds = fileIds.stream()
                            .map(instanceRouter::extractActualId)
                            .collect(Collectors.toList());
                } else {
                    instIndex = instanceRouter.getNextGlobalInstance();
                    actualFileIds = fileIds;
                }

                VectorStoreRequest request = VectorStoreRequest.builder()
                        .name(name)
                        .fileIds(actualFileIds)
                        .build();

                Instance instance = instanceRouter.getInstance(instIndex);
                VectorStore vectorStore = httpHelper.post(
                        instance, ProviderConfig.Endpoint.VECTOR_STORES, null,
                        request, VectorStore.class).join();

                String vectorStoreId = vectorStore.getId();
                logger.info("Created vector store: {} ({}) on instance {}", name, vectorStoreId, instIndex);

                return instanceRouter.encodeWithInstance(instIndex, vectorStoreId);

            } catch (Exception e) {
                logger.error("Failed to create vector store: {}", name, e);
                throw new AgentException(AgentException.ErrorCode.VECTOR_STORE_ERROR,
                        "Failed to create vector store: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Deletes a vector store.
     *
     * @param vectorStoreRef Encoded vector store reference
     * @return true if deleted successfully
     */
    public CompletableFuture<Boolean> deleteVectorStore(String vectorStoreRef) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIndex = instanceRouter.extractInstanceIndex(vectorStoreRef);
                String actualVectorStoreId = instanceRouter.extractActualId(vectorStoreRef);
                Instance instance = instanceRouter.getInstance(instanceIndex);

                Map<String, String> pathParams = new HashMap<>();
                pathParams.put("vectorStoreId", actualVectorStoreId);

                httpHelper.delete(instance, ProviderConfig.Endpoint.VECTOR_STORES, null, pathParams).join();
                logger.info("Deleted vector store: {} from instance {}", actualVectorStoreId, instanceIndex);
                return true;

            } catch (Exception e) {
                logger.error("Failed to delete vector store: {}", vectorStoreRef, e);
                return false;
            }
        });
    }

    /**
     * Extracts the actual file ID from an encoded reference.
     */
    public String extractFileId(String fileRef) {
        return instanceRouter.extractActualId(fileRef);
    }

    /**
     * Extracts the actual vector store ID from an encoded reference.
     */
    public String extractVectorStoreId(String vectorStoreRef) {
        return instanceRouter.extractActualId(vectorStoreRef);
    }
}
