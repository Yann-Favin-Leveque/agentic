package io.github.yannfavinleveque.agentic.agent.custom;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Authentication descriptor for a custom provider.
 *
 * <p>Example JSON:</p>
 * <pre>{@code
 * {
 *   "header": "Authorization",
 *   "format": "Bearer {key}"
 * }
 * }</pre>
 *
 * <p>The {@code {key}} placeholder is substituted with {@code InstanceConfig.key}
 * at request time. If {@code format} is null, the API key is used verbatim.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthSpec {

    /** HTTP header name to set, e.g. "Authorization", "x-api-key", "api-key". */
    @JsonProperty("header")
    private String header;

    /**
     * Header value format. Use {@code {key}} as placeholder for the API key.
     * Examples: "Bearer {key}", "{key}".
     * If null, the API key is used as-is.
     */
    @JsonProperty("format")
    private String format;

    /**
     * Renders the header value by substituting {@code {key}} with the given API key.
     *
     * @param apiKey API key from InstanceConfig
     * @return Final header value
     */
    public String renderValue(String apiKey) {
        if (format == null) {
            return apiKey;
        }
        return format.replace("{key}", apiKey == null ? "" : apiKey);
    }
}
