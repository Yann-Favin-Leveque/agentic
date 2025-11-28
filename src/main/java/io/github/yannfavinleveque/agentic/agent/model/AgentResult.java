package io.github.yannfavinleveque.agentic.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base interface for typed agent responses with JSON Schema support.
 * Implement this interface in your result classes to enable automatic
 * JSON Schema generation and structured output mapping.
 *
 * <p>Example implementation:</p>
 * <pre>{@code
 * public class WeatherResult implements AgentResult {
 *     public String location;
 *     public double temperature;
 *     public String conditions;
 * }
 * }</pre>
 *
 * <p>The implementing class will automatically:
 * <ul>
 *   <li>Generate a JSON Schema for OpenAI structured outputs</li>
 *   <li>Be mapped from JSON responses using Jackson</li>
 *   <li>Support nested objects and collections</li>
 * </ul>
 * </p>
 *
 * @see AgentService # requestAgent(String, Agent)
 */
public interface AgentResult {

    Logger logger = LoggerFactory.getLogger(AgentResult.class);

    /**
     * Default JSON mapper for deserializing agent responses.
     */
    ObjectMapper JSON_MAPPER = new ObjectMapper();

    /**
     * Deserialize JSON string to the specified result class.
     *
     * @param json JSON string from agent response
     * @param clazz Target class implementing AgentResult
     * @param <T> Result type
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
