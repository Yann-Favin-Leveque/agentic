package io.github.sashirestela.openai.agent.v2;

import io.github.sashirestela.openai.agent.Instance;
import io.github.sashirestela.openai.agent.exception.NoInstanceAvailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Routes requests to AI instances with load balancing.
 * Supports per-model round-robin and global round-robin.
 */
public class InstanceRouter {

    private static final Logger logger = LoggerFactory.getLogger(InstanceRouter.class);

    private final List<Instance> instances;
    private final Map<String, AtomicInteger> modelIndexes;
    private final AtomicInteger globalIndex;

    public InstanceRouter(List<Instance> instances) {
        this.instances = instances;
        this.modelIndexes = new ConcurrentHashMap<>();
        this.globalIndex = new AtomicInteger(0);
    }

    /**
     * Gets the next instance for a specific model using round-robin.
     *
     * @param model Model name
     * @return Instance index
     * @throws NoInstanceAvailableException if no instance has the model
     */
    public int getNextInstanceForModel(String model) {
        if (instances.isEmpty()) {
            throw NoInstanceAvailableException.degradedMode();
        }

        // Find instances that have this model
        List<Integer> compatibleIndices = new java.util.ArrayList<>();
        for (int i = 0; i < instances.size(); i++) {
            if (instances.get(i).getDeployedModels().contains(model)) {
                compatibleIndices.add(i);
            }
        }

        if (compatibleIndices.isEmpty()) {
            List<String> availableModels = instances.stream()
                    .flatMap(inst -> inst.getDeployedModels().stream())
                    .distinct()
                    .collect(Collectors.toList());
            throw new NoInstanceAvailableException(model, availableModels);
        }

        // Round-robin within compatible instances
        AtomicInteger counter = modelIndexes.computeIfAbsent(model, k -> new AtomicInteger(0));
        int index = counter.getAndIncrement() % compatibleIndices.size();
        return compatibleIndices.get(index);
    }

    /**
     * Gets the next instance globally (for model-agnostic operations).
     *
     * @return Instance index
     * @throws NoInstanceAvailableException if no instances configured
     */
    public int getNextGlobalInstance() {
        if (instances.isEmpty()) {
            throw NoInstanceAvailableException.degradedMode();
        }
        return globalIndex.getAndIncrement() % instances.size();
    }

    /**
     * Gets an instance by index.
     *
     * @param index Instance index
     * @return Instance
     * @throws NoInstanceAvailableException if index out of bounds
     */
    public Instance getInstance(int index) {
        if (instances.isEmpty()) {
            throw NoInstanceAvailableException.degradedMode();
        }
        if (index < 0 || index >= instances.size()) {
            throw new NoInstanceAvailableException("Invalid instance index: " + index);
        }
        return instances.get(index);
    }

    /**
     * Gets an instance by ID.
     *
     * @param instanceId Instance ID
     * @return Instance
     * @throws NoInstanceAvailableException if not found
     */
    public Instance getInstanceById(String instanceId) {
        return instances.stream()
                .filter(i -> i.getId().equals(instanceId))
                .findFirst()
                .orElseThrow(() -> new NoInstanceAvailableException(
                        "Instance not found: " + instanceId + ". Available: " +
                                instances.stream().map(Instance::getId).collect(Collectors.joining(", "))));
    }

    /**
     * Gets all instances.
     */
    public List<Instance> getInstances() {
        return instances;
    }

    /**
     * Gets the number of instances.
     */
    public int getInstanceCount() {
        return instances.size();
    }

    /**
     * Checks if running in degraded mode (no instances).
     */
    public boolean isDegradedMode() {
        return instances.isEmpty();
    }

    /**
     * Gets all available models across all instances.
     */
    public List<String> getAvailableModels() {
        return instances.stream()
                .flatMap(inst -> inst.getDeployedModels().stream())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Encodes an ID with instance index for multi-instance tracking.
     * Format: "instanceIndex_actualId"
     */
    public String encodeWithInstance(int instanceIndex, String actualId) {
        if (instances.size() > 1) {
            return instanceIndex + "_" + actualId;
        }
        return actualId;
    }

    /**
     * Extracts instance index from an encoded reference.
     * Returns 0 if not encoded.
     */
    public int extractInstanceIndex(String ref) {
        if (ref == null || !ref.contains("_")) {
            return 0;
        }
        int underscoreIndex = ref.indexOf('_');
        try {
            return Integer.parseInt(ref.substring(0, underscoreIndex));
        } catch (NumberFormatException e) {
            logger.warn("Failed to parse instance index from ref: {}", ref);
            return 0;
        }
    }

    /**
     * Extracts the actual ID from an encoded reference.
     */
    public String extractActualId(String ref) {
        if (ref == null || !ref.contains("_")) {
            return ref;
        }
        int underscoreIndex = ref.indexOf('_');
        return ref.substring(underscoreIndex + 1);
    }

    /**
     * Decodes an encoded ID to extract instance index and actual ID.
     * Format: "instanceIndex_actualId"
     *
     * @param encodedId Encoded ID (e.g., "3_thread_abc123")
     * @return Array [instanceIndex, actualId] or null if not encoded
     */
    public String[] decodeInstanceId(String encodedId) {
        if (encodedId == null || !encodedId.contains("_")) {
            return null;
        }
        String[] parts = encodedId.split("_", 2);
        if (parts.length != 2) {
            return null;
        }
        try {
            Integer.parseInt(parts[0]);  // Validate it's a number
            return parts;
        } catch (NumberFormatException e) {
            return null;  // Not encoded, just a regular ID with underscore
        }
    }
}
