package io.github.yannfavinleveque.agentic.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Represents a function call requested by the LLM. When a model wants to call a tool/function,
 * the response will contain FunctionCall objects that can be extracted from AgentResult.
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * AgentResult result = agentService.requestAgent("agent", "What's the weather in Paris?").join();
 *
 * if (result.hasFunctionCalls()) {
 *     for (FunctionCall call : result.getFunctionCalls()) {
 *         String name = call.getName();           // "get_weather"
 *         Map<String, Object> args = call.getArgumentsAsMap(); // {"location": "Paris"}
 *
 *         // Execute your function
 *         String functionResult = myWeatherService.getWeather(args.get("location").toString());
 *
 *         // Continue conversation with function result...
 *     }
 * }
 * }</pre>
 *
 * @see AgentResult#getFunctionCalls()
 * @see AgentResult#hasFunctionCalls()
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FunctionCall {

    private static final Logger logger = LoggerFactory.getLogger(FunctionCall.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Unique ID for this function call (provided by OpenAI, useful for responding).
     * May be null for Claude.
     */
    @JsonProperty("id")
    private String id;

    /**
     * The name of the function to call.
     */
    @JsonProperty("name")
    private String name;

    /**
     * The arguments as a JSON string.
     */
    @JsonProperty("arguments")
    private String arguments;

    /**
     * Parses the arguments JSON string into a Map.
     *
     * @return Map of argument name to value, or empty map if parsing fails
     */
    public Map<String, Object> getArgumentsAsMap() {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(arguments, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            logger.warn("Failed to parse function arguments as Map: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Parses the arguments JSON string into the specified class.
     *
     * @param clazz Target class for deserialization
     * @param <T>   Type parameter
     * @return Deserialized object, or null if parsing fails
     */
    public <T> T getArgumentsAs(Class<T> clazz) {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(arguments, clazz);
        } catch (Exception e) {
            logger.warn("Failed to parse function arguments as {}: {}", clazz.getSimpleName(), e.getMessage());
            return null;
        }
    }

    @Override
    public String toString() {
        return name + "(" + (arguments != null ? arguments : "{}") + ")";
    }
}
