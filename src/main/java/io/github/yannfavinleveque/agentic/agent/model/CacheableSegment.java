package io.github.yannfavinleveque.agentic.agent.model;

import java.util.Objects;

/**
 * A contiguous slice of the user-turn payload, optionally marked as a prompt-cache boundary.
 * <p>
 * The caller splits a large, layered user message (e.g. an agent preamble built from
 * stable-to-volatile zones) into an ordered list of {@code CacheableSegment}s, then hands that
 * list to the segment-aware {@code requestAgent(...)} overloads. Each segment whose
 * {@link #cacheBoundary()} is {@code true} requests a cache breakpoint <em>at the end</em> of
 * that segment — everything from the start of the user content up to and including the boundary
 * becomes a reusable cache prefix.
 * </p>
 * <p>
 * Provider semantics (resolved by the adapters, not by the caller):
 * </p>
 * <ul>
 *   <li><b>Anthropic / Azure Anthropic</b> — each segment becomes a {@code text} content block in
 *       the user message; {@code cache_control: {type: "ephemeral"}} is placed on the last block of
 *       every boundary segment. Anthropic allows at most 4 breakpoints across the whole request
 *       (system + tools already consume some), so the adapter keeps only the <em>last</em>
 *       boundaries that fit and logs a warning if the caller asked for more.</li>
 *   <li><b>OpenAI (Responses) and all other providers</b> — caching is automatic on a stable
 *       prefix (or unsupported), so boundary markers are ignored and the segments are simply
 *       concatenated in order into a single user message. Preserving segment order is the only
 *       thing that matters there.</li>
 * </ul>
 * <p>
 * The legacy {@code String userMessage} overloads delegate to the segment overloads with a single
 * non-boundary segment, so behavior is unchanged when the new API is not used.
 * </p>
 * <p>
 * <b>Note:</b> modeled as an immutable value type with record-style accessors ({@link #text()},
 * {@link #cacheBoundary()}). It is a plain final class rather than a {@code record} because this
 * library targets Java 11 ({@code maven.compiler.release=11}); records require Java 16+.
 * </p>
 */
public final class CacheableSegment {

    private final String text;
    private final boolean cacheBoundary;

    /**
     * @param text          the segment text (must be non-null; may be empty)
     * @param cacheBoundary {@code true} to request a cache breakpoint at the end of this segment
     *                      (honored only by Anthropic providers)
     */
    public CacheableSegment(String text, boolean cacheBoundary) {
        if (text == null) {
            throw new IllegalArgumentException("CacheableSegment text must not be null");
        }
        this.text = text;
        this.cacheBoundary = cacheBoundary;
    }

    /** @return the segment text (never null). */
    public String text() {
        return text;
    }

    /** @return {@code true} if a cache breakpoint is requested at the end of this segment. */
    public boolean cacheBoundary() {
        return cacheBoundary;
    }

    /**
     * Convenience factory for a plain segment that does <em>not</em> request a cache boundary.
     *
     * @param text segment text
     * @return a segment with {@code cacheBoundary = false}
     */
    public static CacheableSegment of(String text) {
        return new CacheableSegment(text, false);
    }

    /**
     * Convenience factory for a segment that requests a cache boundary at its end.
     *
     * @param text segment text
     * @return a segment with {@code cacheBoundary = true}
     */
    public static CacheableSegment boundary(String text) {
        return new CacheableSegment(text, true);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CacheableSegment)) return false;
        CacheableSegment that = (CacheableSegment) o;
        return cacheBoundary == that.cacheBoundary && text.equals(that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, cacheBoundary);
    }

    @Override
    public String toString() {
        return "CacheableSegment[text=" + text + ", cacheBoundary=" + cacheBoundary + "]";
    }

}
