package io.github.yannfavinleveque.agentic.agent;

import io.github.yannfavinleveque.agentic.agent.model.AgentResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgentResult} interface and JSON mapping.
 */
class AgentResultTest {

    /**
     * Test result class for weather data.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WeatherResult implements AgentResult {
        @JsonProperty("city")
        private String city;

        @JsonProperty("temperature")
        private double temperature;

        @JsonProperty("conditions")
        private String conditions;
    }

    /**
     * Test result class for code analysis.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CodeAnalysisResult implements AgentResult {
        @JsonProperty("language")
        private String language;

        @JsonProperty("complexity_score")
        private int complexityScore;

        @JsonProperty("issues")
        private List<String> issues;

        @JsonProperty("suggestions")
        private List<String> suggestions;
    }

    @Test
    void testJsonMapperWithSimpleObject() {
        String json = "{\"city\":\"Paris\",\"temperature\":22.5,\"conditions\":\"Sunny\"}";

        WeatherResult result = AgentResult.jsonMapper(json, WeatherResult.class);

        assertNotNull(result);
        assertEquals("Paris", result.getCity());
        assertEquals(22.5, result.getTemperature());
        assertEquals("Sunny", result.getConditions());
    }

    @Test
    void testJsonMapperWithComplexObject() {
        String json = "{"
                + "\"language\":\"Java\","
                + "\"complexity_score\":7,"
                + "\"issues\":[\"Missing null checks\",\"Unused variable\"],"
                + "\"suggestions\":[\"Add JavaDoc\",\"Extract method\"]"
                + "}";

        CodeAnalysisResult result = AgentResult.jsonMapper(json, CodeAnalysisResult.class);

        assertNotNull(result);
        assertEquals("Java", result.getLanguage());
        assertEquals(7, result.getComplexityScore());
        assertEquals(2, result.getIssues().size());
        assertEquals("Missing null checks", result.getIssues().get(0));
        assertEquals("Add JavaDoc", result.getSuggestions().get(0));
    }

    @Test
    void testJsonMapperWithInvalidJson() {
        String invalidJson = "{invalid json}";

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            AgentResult.jsonMapper(invalidJson, WeatherResult.class);
        });

        assertTrue(exception.getMessage().contains("Failed to map JSON"));
        assertTrue(exception.getMessage().contains("WeatherResult"));
    }

    @Test
    void testJsonMapperWithMissingFields() {
        // JSON with missing 'conditions' field - should use null/default
        String json = "{\"city\":\"London\",\"temperature\":15.0}";

        WeatherResult result = AgentResult.jsonMapper(json, WeatherResult.class);

        assertNotNull(result);
        assertEquals("London", result.getCity());
        assertEquals(15.0, result.getTemperature());
        assertNull(result.getConditions());  // Missing field becomes null
    }

    @Test
    void testJsonMapperWithNullValues() {
        String json = "{\"city\":null,\"temperature\":0.0,\"conditions\":null}";

        WeatherResult result = AgentResult.jsonMapper(json, WeatherResult.class);

        assertNotNull(result);
        assertNull(result.getCity());
        assertEquals(0.0, result.getTemperature());
        assertNull(result.getConditions());
    }

    @Test
    void testJsonMapperWithEmptyObject() {
        String json = "{}";

        WeatherResult result = AgentResult.jsonMapper(json, WeatherResult.class);

        assertNotNull(result);
        assertNull(result.getCity());
        assertEquals(0.0, result.getTemperature());  // Primitive default
        assertNull(result.getConditions());
    }

    @Test
    void testJsonMapperWithSnakeCaseFields() {
        // Test that snake_case JSON fields map correctly to Java camelCase
        String json = "{"
                + "\"language\":\"Python\","
                + "\"complexity_score\":5,"
                + "\"issues\":[],"
                + "\"suggestions\":[\"Use type hints\"]"
                + "}";

        CodeAnalysisResult result = AgentResult.jsonMapper(json, CodeAnalysisResult.class);

        assertNotNull(result);
        assertEquals("Python", result.getLanguage());
        assertEquals(5, result.getComplexityScore());  // complexity_score → complexityScore
        assertTrue(result.getIssues().isEmpty());
        assertEquals(1, result.getSuggestions().size());
    }

    @Test
    void testMultipleJsonMappings() {
        String weatherJson = "{\"city\":\"Tokyo\",\"temperature\":28.0,\"conditions\":\"Cloudy\"}";
        String codeJson = "{\"language\":\"JavaScript\",\"complexity_score\":3,\"issues\":[],\"suggestions\":[]}";

        WeatherResult weatherResult = AgentResult.jsonMapper(weatherJson, WeatherResult.class);
        CodeAnalysisResult codeResult = AgentResult.jsonMapper(codeJson, CodeAnalysisResult.class);

        assertNotNull(weatherResult);
        assertNotNull(codeResult);
        assertEquals("Tokyo", weatherResult.getCity());
        assertEquals("JavaScript", codeResult.getLanguage());
    }

}
