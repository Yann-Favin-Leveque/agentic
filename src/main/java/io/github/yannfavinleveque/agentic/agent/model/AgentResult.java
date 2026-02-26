package io.github.yannfavinleveque.agentic.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yannfavinleveque.agentic.common.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base class for typed agent responses with JSON Schema support. Extend this class in
 * your result classes to enable automatic JSON Schema generation and structured output mapping.
 * <p>
 * Example implementation:
 * </p>
 *
 * <pre>{@code
 * public class WeatherResult extends AgentResult {
 *
 *     public String location;
 *     public double temperature;
 *     public String conditions;
 *
 * }
 * }</pre>
 * <p>
 * The extending class will automatically:
 * <ul>
 * <li>Generate a JSON Schema for OpenAI structured outputs</li>
 * <li>Be mapped from JSON responses using Jackson</li>
 * <li>Support nested objects and collections</li>
 * <li>Carry function calls from the model alongside structured text</li>
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
 * @see FunctionCall
 */
public abstract class AgentResult {

    private static final Logger logger = LoggerFactory.getLogger(AgentResult.class);

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /**
     * Function calls requested by the model.
     * Excluded from JSON schema generation via @JsonIgnore so it doesn't
     * pollute the structured output schema sent to the LLM.
     */
    @JsonIgnore
    private List<FunctionCall> functionCalls = new ArrayList<>();

    /**
     * Token usage and estimated cost for this request.
     * Contains input/output token counts, model name, and estimated cost in USD.
     * Cost is {@code null} when the model is not in the pricing table.
     */
    @JsonIgnore
    private TokenUsage usage;

    /**
     * Gets the content/response from the agent. Subclasses may override for custom behavior.
     * Default implementation returns toString().
     *
     * @return The content string
     */
    public String getContent() {
        return this.toString();
    }

    /**
     * Returns the list of function calls requested by the model.
     *
     * @return List of function calls, or empty list if none
     */
    @JsonIgnore
    public List<FunctionCall> getFunctionCalls() {
        return functionCalls != null ? functionCalls : Collections.emptyList();
    }

    /**
     * Sets the function calls on this result.
     *
     * @param functionCalls List of function calls from the model
     */
    @JsonIgnore
    public void setFunctionCalls(List<FunctionCall> functionCalls) {
        this.functionCalls = functionCalls != null ? functionCalls : new ArrayList<>();
    }

    /**
     * Checks if the model requested any function calls in this response.
     *
     * @return true if there are function calls to process
     */
    @JsonIgnore
    public boolean hasFunctionCalls() {
        return functionCalls != null && !functionCalls.isEmpty();
    }

    /**
     * Returns token usage and estimated cost for this request, or {@code null} if not available.
     *
     * @return Token usage with input/output counts and estimated cost
     */
    @JsonIgnore
    public TokenUsage getUsage() {
        return usage;
    }

    /**
     * Sets the token usage on this result.
     *
     * @param usage Token usage from the API response
     */
    @JsonIgnore
    public void setUsage(TokenUsage usage) {
        this.usage = usage;
    }

    /**
     * Deserialize JSON string to the specified result class.
     *
     * @param json  JSON string from agent response
     * @param clazz Target class extending AgentResult
     * @param <T>   Result type
     * @return Deserialized object
     * @throws RuntimeException if JSON parsing fails
     */
    public static <T extends AgentResult> T jsonMapper(String json, Class<T> clazz) {
        try {
            logger.debug("Attempting to deserialize JSON to {}: {}", clazz.getSimpleName(), json);
            return JSON_MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            logger.error("Failed to deserialize JSON to {}: {}", clazz.getSimpleName(), json, e);
            throw new RuntimeException("Failed to map JSON to " + clazz.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

}
