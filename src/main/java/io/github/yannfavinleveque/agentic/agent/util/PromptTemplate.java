package io.github.yannfavinleveque.agentic.agent.util;

import io.github.yannfavinleveque.agentic.agent.exception.MissingPromptVariableException;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Mustache-style placeholder substitution for agent instructions.
 * <p>
 * Supported syntax:
 * <ul>
 *   <li>{@code {{name}}} — substituted with {@code String.valueOf(promptVars.get("name"))}</li>
 *   <li>{@code {{ name }}} — surrounding whitespace inside the braces is tolerated</li>
 * </ul>
 * Variable names match {@code [a-zA-Z_][a-zA-Z0-9_]*}. Anything else inside braces
 * (dots, dashes, sections, escapes) is left as-is — there is no scoping, no
 * conditional sections, no escape character. This is intentionally minimal:
 * the goal is runtime parameter injection into LLM system prompts, not full
 * Mustache.
 * <p>
 * Substitution is one-pass and non-recursive: a value that itself contains
 * {@code {{x}}} is NOT re-rendered.
 */
public final class PromptTemplate {

    /** Matches {@code {{name}}} with optional whitespace, captures the variable name. */
    public static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\}\\}");

    private PromptTemplate() {
        // utility class
    }

    /**
     * Extracts the set of variable names referenced in the template. Returns an
     * empty set if {@code template} is null or contains no placeholders.
     * Insertion-ordered (LinkedHashSet) so callers get a stable ordering for
     * logging / error messages.
     */
    public static Set<String> extractVariables(String template) {
        if (template == null || template.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> vars = new LinkedHashSet<>();
        Matcher m = VAR_PATTERN.matcher(template);
        while (m.find()) {
            vars.add(m.group(1));
        }
        return vars;
    }

    /**
     * Renders the template, substituting every {@code {{name}}} occurrence with
     * the corresponding value from {@code promptVars} (via
     * {@link String#valueOf(Object)}). A {@code null} value in the map is
     * treated as a missing key and triggers
     * {@link MissingPromptVariableException}.
     *
     * @param template    the raw instructions string; {@code null} returns {@code null}
     * @param promptVars  map of variable name to value; {@code null} is treated as empty
     * @return the substituted string
     * @throws MissingPromptVariableException if the template references a
     *         variable that is not present in {@code promptVars} (or whose value
     *         is {@code null})
     */
    public static String render(String template, Map<String, Object> promptVars) {
        return render(template, promptVars, null);
    }

    /**
     * Same as {@link #render(String, Map)} but attaches the given {@code agentId}
     * to any {@link MissingPromptVariableException} thrown — useful so the
     * caller doesn't have to wrap the exception to add agent context.
     *
     * @param template    the raw instructions string; {@code null} returns {@code null}
     * @param promptVars  map of variable name to value; {@code null} is treated as empty
     * @param agentId     agent id reported in the exception message (may be {@code null})
     * @return the substituted string
     * @throws MissingPromptVariableException if the template references a
     *         variable that is not present in {@code promptVars} (or whose value
     *         is {@code null})
     */
    public static String render(String template, Map<String, Object> promptVars, String agentId) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        Map<String, Object> vars = promptVars != null ? promptVars : Collections.emptyMap();
        Matcher m = VAR_PATTERN.matcher(template);
        if (!m.find()) {
            // Fast path: no placeholders, return the input string unchanged (no allocation).
            return template;
        }
        StringBuffer out = new StringBuffer(template.length());
        do {
            String name = m.group(1);
            // Treat null value the same as missing key — substituting "null" silently
            // would be a footgun for users who rely on a value being set.
            Object value = vars.get(name);
            if (value == null) {
                throw new MissingPromptVariableException(agentId, name, vars.keySet());
            }
            // Matcher.appendReplacement interprets $ and \ in the replacement;
            // quoteReplacement escapes them so user values are inserted verbatim.
            m.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(value)));
        } while (m.find());
        m.appendTail(out);
        return out.toString();
    }
}
