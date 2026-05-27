package io.github.yannfavinleveque.agentic.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stateless parsers for Server-Sent Events (SSE) delta lines emitted by streaming chat APIs.
 *
 * <p>Two wire formats are supported:</p>
 * <ul>
 *   <li><b>OpenAI / Azure OpenAI Chat Completions</b> — lines of the form {@code data: {json}}
 *       where the JSON carries {@code choices[0].delta.content} text fragments, a
 *       {@code choices[0].finish_reason} on the last content chunk, and (when
 *       {@code stream_options.include_usage} is set) a final {@code usage} object on a trailing
 *       chunk with an empty {@code choices} array. The stream ends with a literal
 *       {@code data: [DONE]} line.</li>
 *   <li><b>Anthropic / Azure Anthropic Messages</b> — alternating {@code event: <type>} and
 *       {@code data: {json}} lines. Text arrives on {@code content_block_delta} events
 *       ({@code delta.text}); token usage is split across {@code message_start}
 *       ({@code message.usage.input_tokens}) and {@code message_delta}
 *       ({@code usage.output_tokens}); the stream ends with {@code message_stop}.</li>
 * </ul>
 *
 * <p>Each parser returns a {@link Delta} describing what the line carried: a (possibly empty)
 * text fragment, optional usage figures, optional finish/stop reason, and a {@code done} flag.
 * Lines that are not relevant (blank lines, {@code event:} lines, ping events, unknown types)
 * produce an {@link Delta#empty()}.</p>
 */
public final class StreamDeltaParsers {

    private static final Logger logger = LoggerFactory.getLogger(StreamDeltaParsers.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private StreamDeltaParsers() {
    }

    /**
     * Outcome of parsing a single SSE line.
     */
    public static final class Delta {
        /** Text fragment to stream (never null; empty string when the line carried no text). */
        public final String text;
        /** Cumulative/just-seen input tokens reported on this line, or null. */
        public final Integer inputTokens;
        /** Cumulative/just-seen output tokens reported on this line, or null. */
        public final Integer outputTokens;
        /** Cache-read input tokens (Anthropic), or null. */
        public final Integer cacheReadInputTokens;
        /** Cache-creation input tokens (Anthropic), or null. */
        public final Integer cacheCreationInputTokens;
        /** OpenAI finish_reason / Anthropic stop_reason seen on this line, or null. */
        public final String finishReason;
        /** True when this line marks the end of the stream ([DONE] / message_stop). */
        public final boolean done;

        private Delta(String text, Integer inputTokens, Integer outputTokens,
                Integer cacheReadInputTokens, Integer cacheCreationInputTokens,
                String finishReason, boolean done) {
            this.text = text == null ? "" : text;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.cacheReadInputTokens = cacheReadInputTokens;
            this.cacheCreationInputTokens = cacheCreationInputTokens;
            this.finishReason = finishReason;
            this.done = done;
        }

        static Delta empty() {
            return new Delta("", null, null, null, null, null, false);
        }

        static Delta ofText(String text) {
            return new Delta(text, null, null, null, null, null, false);
        }

        boolean hasUsage() {
            return inputTokens != null || outputTokens != null
                    || cacheReadInputTokens != null || cacheCreationInputTokens != null;
        }
    }

    /**
     * Strips a leading {@code "data:"} (optionally followed by a space) from an SSE line.
     * Returns null when the line is not a data line.
     */
    private static String stripDataPrefix(String line) {
        if (line == null) return null;
        String trimmed = line.stripLeading();
        if (trimmed.startsWith("data:")) {
            return trimmed.substring("data:".length()).stripLeading();
        }
        return null;
    }

    /**
     * Parses one OpenAI / Azure OpenAI Chat Completions SSE line.
     *
     * @param sseLine raw line as received from the stream
     * @return parsed {@link Delta}; {@link Delta#done} is true on the {@code [DONE]} sentinel
     */
    public static Delta parseOpenAIDelta(String sseLine) {
        String payload = stripDataPrefix(sseLine);
        if (payload == null || payload.isEmpty()) {
            return Delta.empty();
        }
        if ("[DONE]".equals(payload)) {
            return new Delta("", null, null, null, null, null, true);
        }
        try {
            JsonNode root = MAPPER.readTree(payload);

            String text = "";
            String finishReason = null;
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode first = choices.get(0);
                JsonNode delta = first.get("delta");
                if (delta != null) {
                    JsonNode content = delta.get("content");
                    if (content != null && content.isTextual()) {
                        text = content.asText();
                    }
                }
                JsonNode fr = first.get("finish_reason");
                if (fr != null && fr.isTextual()) {
                    finishReason = fr.asText();
                }
            }

            Integer in = null;
            Integer out = null;
            Integer cacheRead = null;
            JsonNode usage = root.get("usage");
            if (usage != null && !usage.isNull()) {
                if (usage.hasNonNull("prompt_tokens")) in = usage.get("prompt_tokens").asInt();
                if (usage.hasNonNull("completion_tokens")) out = usage.get("completion_tokens").asInt();
                JsonNode details = usage.get("prompt_tokens_details");
                if (details != null && details.hasNonNull("cached_tokens")) {
                    cacheRead = details.get("cached_tokens").asInt();
                }
            }

            if (text.isEmpty() && finishReason == null && in == null && out == null && cacheRead == null) {
                return Delta.empty();
            }
            return new Delta(text, in, out, cacheRead, null, finishReason, false);
        } catch (Exception e) {
            logger.debug("parseOpenAIDelta: could not parse line (ignored): {}", e.getMessage());
            return Delta.empty();
        }
    }

    /**
     * Parses one Anthropic / Azure Anthropic Messages SSE line.
     *
     * <p>Only {@code data:} lines carry JSON; {@code event:} lines are ignored (the type is also
     * present inside the JSON via the {@code type} field, which is what we key on).</p>
     *
     * @param sseLine raw line as received from the stream
     * @return parsed {@link Delta}; {@link Delta#done} is true on {@code message_stop}
     */
    public static Delta parseAnthropicDelta(String sseLine) {
        String payload = stripDataPrefix(sseLine);
        if (payload == null || payload.isEmpty()) {
            // event: lines, blank lines — ignored.
            return Delta.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(payload);
            String type = root.path("type").asText("");

            switch (type) {
                case "content_block_delta": {
                    JsonNode delta = root.get("delta");
                    if (delta != null) {
                        // text_delta carries delta.text; input_json_delta / thinking deltas are ignored here.
                        JsonNode text = delta.get("text");
                        if (text != null && text.isTextual()) {
                            return Delta.ofText(text.asText());
                        }
                    }
                    return Delta.empty();
                }
                case "message_start": {
                    JsonNode usage = root.path("message").path("usage");
                    return usageDelta(usage, null, false);
                }
                case "message_delta": {
                    // usage.output_tokens is cumulative here; stop_reason lives under delta.
                    JsonNode usage = root.get("usage");
                    String stopReason = null;
                    JsonNode delta = root.get("delta");
                    if (delta != null && delta.hasNonNull("stop_reason")) {
                        stopReason = delta.get("stop_reason").asText();
                    }
                    return usageDelta(usage, stopReason, false);
                }
                case "message_stop":
                    return new Delta("", null, null, null, null, null, true);
                default:
                    // ping, content_block_start, content_block_stop, error, etc.
                    return Delta.empty();
            }
        } catch (Exception e) {
            logger.debug("parseAnthropicDelta: could not parse line (ignored): {}", e.getMessage());
            return Delta.empty();
        }
    }

    private static Delta usageDelta(JsonNode usage, String stopReason, boolean done) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            if (stopReason == null) return Delta.empty();
            return new Delta("", null, null, null, null, stopReason, done);
        }
        Integer in = usage.hasNonNull("input_tokens") ? usage.get("input_tokens").asInt() : null;
        Integer out = usage.hasNonNull("output_tokens") ? usage.get("output_tokens").asInt() : null;
        Integer cacheRead = usage.hasNonNull("cache_read_input_tokens")
                ? usage.get("cache_read_input_tokens").asInt() : null;
        Integer cacheCreate = usage.hasNonNull("cache_creation_input_tokens")
                ? usage.get("cache_creation_input_tokens").asInt() : null;
        return new Delta("", in, out, cacheRead, cacheCreate, stopReason, done);
    }
}
