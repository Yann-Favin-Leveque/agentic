package io.github.yannfavinleveque.agentic.agent.custom;

/**
 * Defines how the library reacts when an agent requests a feature that the
 * (custom) provider has not declared as supported.
 */
public enum LenientMode {
    /** Throw {@link io.github.yannfavinleveque.agentic.agent.exception.UnsupportedFeatureException}. Default. */
    THROW,
    /** Log a warning, drop the unsupported feature, and proceed. */
    WARN,
    /** Drop the unsupported feature silently. Use with care - hard to debug later. */
    IGNORE;

    /**
     * Parses a JSON value (case-insensitive). Returns {@link #THROW} on null
     * or unrecognized input.
     */
    public static LenientMode fromString(String s) {
        if (s == null) return THROW;
        switch (s.trim().toLowerCase()) {
            case "throw":  return THROW;
            case "warn":   return WARN;
            case "ignore": return IGNORE;
            default:       return THROW;
        }
    }
}
