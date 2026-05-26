package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.agent.model.CacheableSegment;
import io.github.yannfavinleveque.agentic.agent.model.ClaudeRequest.ClaudeContentBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ClaudeAdapter}'s segment → content-block transformation used by the
 * multi-breakpoint prompt-caching API.
 * <ul>
 *   <li>{@link ClaudeAdapter#buildUserContentBlocks(List)} — Anthropic path: one text block per
 *       segment, {@code cache_control:ephemeral} on boundary segments, respecting the 2-breakpoint
 *       user-turn cap (last boundaries win).</li>
 *   <li>{@link ClaudeAdapter#concatSegments(List)} — non-Anthropic path: order-preserving
 *       concatenation, no markers.</li>
 * </ul>
 */
class ClaudeAdapterCacheSegmentTest {

    private static boolean isCached(ClaudeContentBlock block) {
        return block.getCacheControl() != null
                && "ephemeral".equals(block.getCacheControl().get("type"));
    }

    @Nested
    @DisplayName("buildUserContentBlocks (Anthropic)")
    class BuildUserContentBlocks {

        @Test
        @DisplayName("single non-boundary segment -> one plain text block, no cache_control")
        void singlePlainSegment() {
            List<ClaudeContentBlock> blocks = ClaudeAdapter.buildUserContentBlocks(
                    List.of(new CacheableSegment("hello", false)));

            assertEquals(1, blocks.size());
            assertEquals("text", blocks.get(0).getType());
            assertEquals("hello", blocks.get(0).getText());
            assertNull(blocks.get(0).getCacheControl(), "plain segment must not carry cache_control");
        }

        @Test
        @DisplayName("each segment becomes its own text block, order preserved")
        void oneBlockPerSegmentInOrder() {
            List<ClaudeContentBlock> blocks = ClaudeAdapter.buildUserContentBlocks(List.of(
                    new CacheableSegment("A", false),
                    new CacheableSegment("B", false),
                    new CacheableSegment("C", false)));

            assertEquals(3, blocks.size());
            assertEquals("A", blocks.get(0).getText());
            assertEquals("B", blocks.get(1).getText());
            assertEquals("C", blocks.get(2).getText());
        }

        @Test
        @DisplayName("cache_control is placed at boundary segments only")
        void cacheControlAtBoundaries() {
            List<ClaudeContentBlock> blocks = ClaudeAdapter.buildUserContentBlocks(List.of(
                    new CacheableSegment("long-memory", true),   // boundary #1
                    new CacheableSegment("medium-memory", true),  // boundary #2
                    new CacheableSegment("volatile-tail", false)));

            assertEquals(3, blocks.size());
            assertTrue(isCached(blocks.get(0)), "long-memory boundary must be cached");
            assertTrue(isCached(blocks.get(1)), "medium-memory boundary must be cached");
            assertNull(blocks.get(2).getCacheControl(), "volatile tail must not be cached");
        }

        @Test
        @DisplayName("respects the 2-breakpoint user cap: keeps the LAST two boundaries")
        void capsAtTwoKeepingLast() {
            // 4 boundaries requested -> only the last 2 honored.
            List<ClaudeContentBlock> blocks = ClaudeAdapter.buildUserContentBlocks(List.of(
                    new CacheableSegment("seg0", true),  // dropped
                    new CacheableSegment("seg1", true),  // dropped
                    new CacheableSegment("seg2", true),  // kept
                    new CacheableSegment("seg3", true))); // kept

            assertEquals(4, blocks.size());
            assertNull(blocks.get(0).getCacheControl(), "earliest boundary should be dropped");
            assertNull(blocks.get(1).getCacheControl(), "2nd boundary should be dropped");
            assertTrue(isCached(blocks.get(2)), "3rd boundary (2nd-to-last) should be kept");
            assertTrue(isCached(blocks.get(3)), "last boundary should be kept");
        }

        @Test
        @DisplayName("exactly 2 boundaries are both honored (no drop, no warn)")
        void exactlyTwoHonored() {
            List<ClaudeContentBlock> blocks = ClaudeAdapter.buildUserContentBlocks(List.of(
                    new CacheableSegment("a", true),
                    new CacheableSegment("b", false),
                    new CacheableSegment("c", true)));

            assertTrue(isCached(blocks.get(0)));
            assertNull(blocks.get(1).getCacheControl());
            assertTrue(isCached(blocks.get(2)));
        }

        @Test
        @DisplayName("empty boundary segment is preserved as an empty cached text block")
        void emptyBoundarySegmentPreserved() {
            List<ClaudeContentBlock> blocks = ClaudeAdapter.buildUserContentBlocks(List.of(
                    new CacheableSegment("body", false),
                    new CacheableSegment("", true)));

            assertEquals(2, blocks.size());
            assertEquals("", blocks.get(1).getText());
            assertTrue(isCached(blocks.get(1)), "empty boundary must still carry the breakpoint");
        }

        @Test
        @DisplayName("null or empty segment list throws IllegalArgumentException")
        void rejectsNullOrEmpty() {
            assertThrows(IllegalArgumentException.class,
                    () -> ClaudeAdapter.buildUserContentBlocks(null));
            assertThrows(IllegalArgumentException.class,
                    () -> ClaudeAdapter.buildUserContentBlocks(new ArrayList<>()));
        }
    }

    @Nested
    @DisplayName("concatSegments (non-Anthropic)")
    class ConcatSegments {

        @Test
        @DisplayName("concatenates segments in order, ignoring boundary markers")
        void concatInOrder() {
            String out = ClaudeAdapter.concatSegments(List.of(
                    new CacheableSegment("Hello, ", true),
                    new CacheableSegment("world", false),
                    new CacheableSegment("!", true)));

            assertEquals("Hello, world!", out);
        }

        @Test
        @DisplayName("single segment -> its text verbatim")
        void singleSegment() {
            assertEquals("just-this",
                    ClaudeAdapter.concatSegments(List.of(new CacheableSegment("just-this", false))));
        }

        @Test
        @DisplayName("null or empty list -> empty string")
        void nullOrEmpty() {
            assertEquals("", ClaudeAdapter.concatSegments(null));
            assertEquals("", ClaudeAdapter.concatSegments(new ArrayList<>()));
        }

        @Test
        @DisplayName("matches the original single-string user message for one plain segment")
        void roundTripWithSingleSegment() {
            String original = "the whole preamble as one string";
            String concatenated = ClaudeAdapter.concatSegments(
                    List.of(new CacheableSegment(original, false)));
            assertEquals(original, concatenated);
        }
    }

    @Nested
    @DisplayName("CacheableSegment value type")
    class ValueType {

        @Test
        @DisplayName("accessors, factories, equals/hashCode")
        void valueSemantics() {
            CacheableSegment a = CacheableSegment.of("x");
            CacheableSegment b = new CacheableSegment("x", false);
            CacheableSegment c = CacheableSegment.boundary("x");

            assertEquals("x", a.text());
            assertEquals(false, a.cacheBoundary());
            assertEquals(true, c.cacheBoundary());
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotNull(a.toString());
        }

        @Test
        @DisplayName("null text rejected")
        void nullTextRejected() {
            assertThrows(IllegalArgumentException.class, () -> new CacheableSegment(null, false));
        }
    }
}
