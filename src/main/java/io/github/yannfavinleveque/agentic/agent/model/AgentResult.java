package io.github.yannfavinleveque.agentic.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * Base interface for typed agent responses with JSON Schema support. Implement this interface in
 * your result classes to enable automatic JSON Schema generation and structured output mapping.
 * <p>
 * Example implementation:
 * </p>
 *
 * <pre>{@code
 * public class WeatherResult implements AgentResult {
 *
 *     public String location;
 *     public double temperature;
 *     public String conditions;
 *
 * }
 * }</pre>
 * <p>
 * The implementing class will automatically:
 * <ul>
 * <li>Generate a JSON Schema for OpenAI structured outputs</li>
 * <li>Be mapped from JSON responses using Jackson</li>
 * <li>Support nested objects and collections</li>
 * </ul>
 * </p>
 *
 * <p>
 * Function calls: When the model requests to call a function/tool, use {@link #hasFunctionCalls()}
 * and {@link #getFunctionCalls()} to access the requested calls:
 * </p>
 * <pre>{@code
 * AgentResult result = agentService.requestAgent("agent", "What's the weather?").join();
 * if (result.hasFunctionCalls()) {
 *     for (FunctionCall call : result.getFunctionCalls()) {
 *         String name = call.getName();
 *         Map<String, Object> args = call.getArgumentsAsMap();
 *         // Execute function and continue conversation...
 *     }
 * }
 * }</pre>
 *
 * @see AgentService # requestAgent(String, Agent)
 * @see FunctionCall
 */
public interface AgentResult {

    Logger logger = LoggerFactory.getLogger(AgentResult.class);

    /**
     * Default JSON mapper for deserializing agent responses.
     */
    ObjectMapper JSON_MAPPER = new ObjectMapper();

    /**
     * Gets the content/response from the agent. Implementations should return the primary text content.
     * Default implementation returns toString() for backward compatibility.
     *
     * @return The content string
     */
    default String getContent() {
        return this.toString();
    }

    /**
     * Returns the list of function calls requested by the model. When the model wants to invoke
     * tools/functions, this list will contain the function names and arguments.
     *
     * @return List of function calls, or empty list if none
     */
    default List<FunctionCall> getFunctionCalls() {
        return Collections.emptyList();
    }

    /**
     * Checks if the model requested any function calls in this response.
     *
     * @return true if there are function calls to process
     */
    default boolean hasFunctionCalls() {
        List<FunctionCall> calls = getFunctionCalls();
        return calls != null && !calls.isEmpty();
    }

    /**
     * Deserialize JSON string to the specified result class.
     *
     * @param json  JSON string from agent response
     * @param clazz Target class implementing AgentResult
     * @param <T>   Result type
     * @return Deserialized object
     * @throws RuntimeException if JSON parsing fails
     */
    static <T extends AgentResult> T jsonMapper(String json, Class<T> clazz) {
        try {
            logger.debug("Attempting to deserialize JSON to {}: {}", clazz.getSimpleName(), json);
            return JSON_MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            logger.error("Failed to deserialize JSON to {}: {}", clazz.getSimpleName(), json, e);
            throw new RuntimeException("Failed to map JSON to " + clazz.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

}
