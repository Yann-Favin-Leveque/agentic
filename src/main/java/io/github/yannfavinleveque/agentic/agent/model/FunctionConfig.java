package io.github.yannfavinleveque.agentic.agent.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for a custom function that can be called by an agent. Functions are defined in JSON
 * agent definitions and map to Java method classes.
 * <p>
 * Example JSON configuration:
 * </p>
 * 
 * <pre>{@code
 * {
 *   "functions": [
 *     {
 *       "name": "get_weather",
 *       "description": "Get current weather for a location",
 *       "methodClass": "com.example.functions.WeatherFunction"
 *     }
 *   ]
 * }
 * }</pre>
 * <p>
 * The methodClass must implement a method with parameters matching the function schema.
 * </p>
 *
 * @see AgentDefinition
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FunctionConfig {

    /**
     * Function name used in API calls. Must be unique within an agent's function list.
     */
    @JsonProperty("name")
    private String name;

    /**
     * Human-readable description of what the function does. This is sent to the LLM to help it decide
     * when to call the function.
     */
    @JsonProperty("description")
    private String description;

    /**
     * Fully qualified class name of the Java class implementing this function. The class should have a
     * method that can be invoked with the function parameters.
     * <p>
     * Example: "com.example.functions.WeatherFunction"
     * </p>
     */
    @JsonProperty("methodClass")
    private String methodClass;

    /**
     * Optional: specific method name to invoke on the class. If not specified, defaults to "execute" or
     * the function name.
     */
    @JsonProperty("methodName")
    private String methodName;

    /**
     * Optional: parameter class for structured input. If specified, function arguments will be
     * deserialized to this class.
     */
    @JsonProperty("parameterClass")
    private String parameterClass;

    /**
     * Optional: inline JSON schema for function parameters. Use this when you don't want to create
     * a dedicated parameter class. If both parameterClass and parameters are specified,
     * parameterClass takes precedence.
     * <p>
     * Example:
     * </p>
     *
     * <pre>{@code
     * {
     *   "type": "object",
     *   "properties": {
     *     "location": { "type": "string", "description": "City name" }
     *   },
     *   "required": ["location"]
     * }
     * }</pre>
     */
    @JsonProperty("parameters")
    private java.util.Map<String, Object> parameters;

    /**
     * If {@code true}, calling this tool ENDS the current autonomous turn:
     * the tool is executed, its result is stored in the conversation, and the
     * autonomous loop returns immediately (no further LLM iteration).
     * <p>
     * Use this for tools that represent a natural end-of-turn:
     * <ul>
     *   <li>{@code ask_user} — hand control back to the user for input</li>
     *   <li>{@code task_complete} — signal a milestone and pause</li>
     *   <li>Custom "final answer" or "handoff" tools</li>
     * </ul>
     * Replaces the legacy hardcoded {@code task_over} mechanism: if NO function has
     * {@code endsTurn=true} and the agent is autonomous, the library auto-injects a
     * {@code task_over} function with {@code endsTurn=true} for backwards compatibility.
     * <p>
     * Default: {@code false}.
     */
    @JsonProperty("endsTurn")
    @JsonAlias("ends_turn")
    @lombok.Builder.Default
    private Boolean endsTurn = false;

    /**
     * Optional: fully qualified class name (or simple name) of a class implementing
     * {@link ToolExecutor}. Used in autonomous agent mode to execute this function
     * without requiring a lambda at the call site.
     * <p>
     * If both a lambda {@code ToolExecutor} and an {@code executorClass} are provided,
     * the lambda takes priority.
     * </p>
     * <p>
     * The class must have a public no-arg constructor. If the name does not contain '.',
     * the configured {@code functionExecutorClassPackage} is prepended.
     * </p>
     */
    @JsonProperty("executorClass")
    @JsonAlias("executor_class")
    private String executorClass;

}
