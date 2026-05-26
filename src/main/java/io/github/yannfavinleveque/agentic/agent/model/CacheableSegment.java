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
 * A segment is either a <b>text</b> segment (the default, carrying {@link #text()}) or an
 * <b>image</b> segment (carrying {@link #imageBase64()} + {@link #mimeType()}), created via
 * {@link #image(String, String)}. An image segment is rendered by the Anthropic adapter as a real
 * {@code image} content block (the model sees the pixels) and is <em>never</em> marked
 * {@code cache_control} — an image is large and non-cacheable, so it must always stay in the
 * volatile tail of the request and never anchor a cache prefix. Callers therefore must not mark an
 * image segment as a boundary; {@link #image(String, String)} always produces a non-boundary
 * segment. For non-Anthropic providers (see below) an image segment degrades to a small
 * {@code [image]} text placeholder.
 * </p>
 * <p>
 * Provider semantics (resolved by the adapters, not by the caller):
 * </p>
 * <ul>
 *   <li><b>Anthropic / Azure Anthropic</b> — each text segment becomes a {@code text} content block
 *       in the user message; each image segment becomes an {@code image} content block (base64
 *       source). {@code cache_control: {type: "ephemeral"}} is placed on the last block of every
 *       boundary <em>text</em> segment. Image segments never receive {@code cache_control}.
 *       Anthropic allows at most 4 breakpoints across the whole request (system + tools already
 *       consume some), so the adapter keeps only the <em>last</em> boundaries that fit and logs a
 *       warning if the caller asked for more.</li>
 *   <li><b>OpenAI (Responses) and all other providers</b> — caching is automatic on a stable
 *       prefix (or unsupported), so boundary markers are ignored and the segments are simply
 *       concatenated in order into a single user message. Image segments degrade to a short
 *       {@code [image]} text placeholder (the segment-based vision path is Anthropic-first); the
 *       multimodal {@code requestAgentVision(...)} overloads remain the supported way to send
 *       images to OpenAI. Preserving segment order is the only thing that matters there.</li>
 * </ul>
 * <p>
 * The legacy {@code String userMessage} overloads delegate to the segment overloads with a single
 * non-boundary text segment, so behavior is unchanged when the new API is not used.
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
    private final String imageBase64;
    private final String mimeType;

    /**
     * Builds a text segment.
     *
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
        this.imageBase64 = null;
        this.mimeType = null;
    }

    /**
     * Full constructor (text or image). Exactly one of (text) / (imageBase64 + mimeType) is the
     * payload; an image segment carries an empty {@link #text()} and is never a cache boundary.
     */
    private CacheableSegment(String text, boolean cacheBoundary, String imageBase64, String mimeType) {
        this.text = text;
        this.cacheBoundary = cacheBoundary;
        this.imageBase64 = imageBase64;
        this.mimeType = mimeType;
    }

    /** @return the segment text (never null; empty string for image segments). */
    public String text() {
        return text;
    }

    /** @return {@code true} if a cache breakpoint is requested at the end of this segment. */
    public boolean cacheBoundary() {
        return cacheBoundary;
    }

    /**
     * @return the base64-encoded image data for an image segment, or {@code null} for a text
     *         segment.
     */
    public String imageBase64() {
        return imageBase64;
    }

    /**
     * @return the image media type (e.g. {@code "image/png"}) for an image segment, or {@code null}
     *         for a text segment.
     */
    public String mimeType() {
        return mimeType;
    }

    /**
     * @return {@code true} if this is an image segment (carries base64 image data), {@code false}
     *         for a text segment.
     */
    public boolean isImage() {
        return imageBase64 != null;
    }

    /**
     * Convenience factory for a plain text segment that does <em>not</em> request a cache boundary.
     *
     * @param text segment text
     * @return a segment with {@code cacheBoundary = false}
     */
    public static CacheableSegment of(String text) {
        return new CacheableSegment(text, false);
    }

    /**
     * Convenience factory for a text segment that requests a cache boundary at its end.
     *
     * @param text segment text
     * @return a segment with {@code cacheBoundary = true}
     */
    public static CacheableSegment boundary(String text) {
        return new CacheableSegment(text, true);
    }

    /**
     * Factory for an <b>image</b> segment. The image is rendered as a real {@code image} content
     * block by the Anthropic adapter (the model sees it) and is always non-boundary / never
     * cached: an image is large and non-cacheable, so it must stay in the volatile tail and never
     * anchor a cache prefix.
     *
     * @param imageBase64 base64-encoded image data (must be non-null/non-empty)
     * @param mimeType    image media type, e.g. {@code "image/png"} (must be non-null/non-empty)
     * @return a non-boundary image segment
     */
    public static CacheableSegment image(String imageBase64, String mimeType) {
        if (imageBase64 == null || imageBase64.isEmpty()) {
            throw new IllegalArgumentException("CacheableSegment image base64 must not be null or empty");
        }
        if (mimeType == null || mimeType.isEmpty()) {
            throw new IllegalArgumentException("CacheableSegment image mimeType must not be null or empty");
        }
        return new CacheableSegment("", false, imageBase64, mimeType);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CacheableSegment)) return false;
        CacheableSegment that = (CacheableSegment) o;
        return cacheBoundary == that.cacheBoundary
                && text.equals(that.text)
                && Objects.equals(imageBase64, that.imageBase64)
                && Objects.equals(mimeType, that.mimeType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, cacheBoundary, imageBase64, mimeType);
    }

    @Override
    public String toString() {
        if (isImage()) {
            return "CacheableSegment[image=" + mimeType + ", base64Len="
                    + imageBase64.length() + "]";
        }
        return "CacheableSegment[text=" + text + ", cacheBoundary=" + cacheBoundary + "]";
    }

}
