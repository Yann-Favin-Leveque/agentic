package io.github.yannfavinleveque.agentic.agent.custom;

import io.github.yannfavinleveque.agentic.agent.exception.AgentException;
import io.github.yannfavinleveque.agentic.agent.exception.UnsupportedFeatureException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link UnsupportedFeatureException}.
 */
class UnsupportedFeatureExceptionTest {

    @Test
    @DisplayName("constructor populates all accessors and produces an actionable message")
    void constructor_populatesAccessorsAndMessage() {
        Set<Feature> supported = EnumSet.of(Feature.VISION);
        UnsupportedFeatureException ex = new UnsupportedFeatureException(
                "my-instance", Feature.WEB_SEARCH, supported);

        assertEquals("my-instance", ex.getInstanceId());
        assertSame(Feature.WEB_SEARCH, ex.getFeature());
        assertEquals(supported, ex.getSupportedFeatures());

        String msg = ex.getMessage();
        assertTrue(msg.contains("my-instance"), "message should contain instanceId, was: " + msg);
        assertTrue(msg.contains("WEB_SEARCH"), "message should contain feature, was: " + msg);
        assertTrue(msg.contains("VISION"), "message should mention supported feature, was: " + msg);
        assertTrue(msg.contains("onUnsupportedFeature"),
                "message should mention onUnsupportedFeature, was: " + msg);
    }

    @Test
    @DisplayName("getErrorCode returns UNSUPPORTED_FEATURE")
    void getErrorCode_returnsUnsupportedFeature() {
        UnsupportedFeatureException ex = new UnsupportedFeatureException(
                "x", Feature.STREAMING, EnumSet.noneOf(Feature.class));
        assertEquals(AgentException.ErrorCode.UNSUPPORTED_FEATURE, ex.getErrorCode());
    }
}
