package io.github.yannfavinleveque.agentic.agent.custom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link LenientMode#fromString(String)}.
 */
class LenientModeTest {

    @Test
    @DisplayName("fromString recognizes throw/warn/ignore (lowercase)")
    void fromString_lowercase_recognized() {
        assertEquals(LenientMode.THROW, LenientMode.fromString("throw"));
        assertEquals(LenientMode.WARN, LenientMode.fromString("warn"));
        assertEquals(LenientMode.IGNORE, LenientMode.fromString("ignore"));
    }

    @Test
    @DisplayName("fromString is case-insensitive")
    void fromString_caseInsensitive() {
        assertEquals(LenientMode.WARN, LenientMode.fromString("WARN"));
        assertEquals(LenientMode.IGNORE, LenientMode.fromString("Ignore"));
        assertEquals(LenientMode.THROW, LenientMode.fromString("Throw"));
    }

    @Test
    @DisplayName("fromString(null) returns THROW")
    void fromString_null_returnsThrow() {
        assertEquals(LenientMode.THROW, LenientMode.fromString(null));
    }

    @Test
    @DisplayName("fromString of unknown value returns THROW (fallback)")
    void fromString_unknown_returnsThrow() {
        assertEquals(LenientMode.THROW, LenientMode.fromString("invalid"));
    }
}
