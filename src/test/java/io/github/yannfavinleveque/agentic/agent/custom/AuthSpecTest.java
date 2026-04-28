package io.github.yannfavinleveque.agentic.agent.custom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link AuthSpec#renderValue(String)}.
 */
class AuthSpecTest {

    @Test
    @DisplayName("renderValue substitutes {key} placeholder with apiKey")
    void renderValue_withFormatAndKey_substitutesPlaceholder() {
        AuthSpec spec = AuthSpec.builder()
                .header("Authorization")
                .format("Bearer {key}")
                .build();
        assertEquals("Bearer abc", spec.renderValue("abc"));
    }

    @Test
    @DisplayName("renderValue with null format returns apiKey verbatim")
    void renderValue_nullFormat_returnsApiKeyVerbatim() {
        AuthSpec spec = AuthSpec.builder()
                .header("x-api-key")
                .format(null)
                .build();
        assertEquals("abc", spec.renderValue("abc"));
    }

    @Test
    @DisplayName("renderValue with null apiKey substitutes empty string")
    void renderValue_nullKey_substitutesEmpty() {
        AuthSpec spec = AuthSpec.builder()
                .header("Authorization")
                .format("Bearer {key}")
                .build();
        assertEquals("Bearer ", spec.renderValue(null));
    }
}
