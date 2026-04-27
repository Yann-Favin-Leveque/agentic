package io.github.yannfavinleveque.agentic.agent.util;

import io.github.yannfavinleveque.agentic.agent.exception.AgentException;
import io.github.yannfavinleveque.agentic.agent.exception.MissingPromptVariableException;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PromptTemplate}.
 */
class PromptTemplateTest {

    // ==================== render() ====================

    @Test
    void render_singleVariable_substitutes() {
        String out = PromptTemplate.render("Hello {{foo}}", Map.of("foo", "bar"));
        assertEquals("Hello bar", out);
    }

    @Test
    void render_whitespaceInsideBraces_tolerated() {
        String out = PromptTemplate.render("Hello {{ foo }}", Map.of("foo", "bar"));
        assertEquals("Hello bar", out);
    }

    @Test
    void render_multipleVariables_allSubstituted() {
        // LinkedHashMap so iteration order is deterministic for the assertion message
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("a", "1");
        vars.put("b", "2");
        String out = PromptTemplate.render("{{a}} and {{b}}", vars);
        assertEquals("1 and 2", out);
    }

    @Test
    void render_repeatedVariable_substitutedEachOccurrence() {
        String out = PromptTemplate.render("{{x}}-{{x}}-{{x}}", Map.of("x", "z"));
        assertEquals("z-z-z", out);
    }

    @Test
    void render_missingVariable_throwsMissingPromptVariableException() {
        MissingPromptVariableException ex = assertThrows(
                MissingPromptVariableException.class,
                () -> PromptTemplate.render("Hello {{foo}}", Collections.emptyMap())
        );
        assertEquals("foo", ex.getVariableName());
        assertTrue(ex.getProvidedKeys().isEmpty());
        // Inherits AgentException for unified handling
        assertTrue(ex instanceof AgentException);
        assertEquals(AgentException.ErrorCode.MISSING_PROMPT_VARIABLE, ex.getErrorCode());
    }

    @Test
    void render_missingVariable_exceptionCarriesAgentIdAndProvidedKeys() {
        Map<String, Object> provided = new HashMap<>();
        provided.put("a", "1");
        provided.put("b", "2");
        MissingPromptVariableException ex = assertThrows(
                MissingPromptVariableException.class,
                () -> PromptTemplate.render("{{missing}}", provided, "agent-42")
        );
        assertEquals("agent-42", ex.getAgentId());
        assertEquals("missing", ex.getVariableName());
        assertEquals(Set.of("a", "b"), ex.getProvidedKeys());
        assertTrue(ex.getMessage().contains("agent-42"));
        assertTrue(ex.getMessage().contains("{{missing}}"));
    }

    @Test
    void render_emptyMapAndTemplateWithoutVars_returnsTemplateUnchanged() {
        String template = "no placeholders here";
        String out = PromptTemplate.render(template, Collections.emptyMap());
        assertEquals(template, out);
    }

    @Test
    void render_nullMapAndTemplateWithoutVars_returnsTemplateUnchanged() {
        String template = "no placeholders here";
        String out = PromptTemplate.render(template, null);
        assertEquals(template, out);
    }

    @Test
    void render_nullTemplate_returnsNull() {
        assertNull(PromptTemplate.render(null, Map.of("foo", "bar")));
    }

    @Test
    void render_emptyTemplate_returnsEmpty() {
        assertEquals("", PromptTemplate.render("", Map.of("foo", "bar")));
    }

    @Test
    void render_integerValue_convertedViaStringValueOf() {
        String out = PromptTemplate.render("answer={{n}}", Map.of("n", 42));
        assertEquals("answer=42", out);
    }

    @Test
    void render_booleanValue_convertedViaStringValueOf() {
        String out = PromptTemplate.render("flag={{flag}}", Map.of("flag", true));
        assertEquals("flag=true", out);
    }

    @Test
    void render_nullValueInMap_treatedAsMissing() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("foo", null);
        MissingPromptVariableException ex = assertThrows(
                MissingPromptVariableException.class,
                () -> PromptTemplate.render("Hello {{foo}}", vars)
        );
        assertEquals("foo", ex.getVariableName());
        // Even though the key is present in the map, null is rejected the same as missing
        assertTrue(ex.getProvidedKeys().contains("foo"));
    }

    @Test
    void render_dollarSignInValue_insertedVerbatim() {
        // Matcher.quoteReplacement guards against $ being interpreted as a back-reference
        String out = PromptTemplate.render("price={{p}}", Map.of("p", "$100"));
        assertEquals("price=$100", out);
    }

    @Test
    void render_backslashInValue_insertedVerbatim() {
        String out = PromptTemplate.render("path={{p}}", Map.of("p", "C:\\tmp\\x"));
        assertEquals("path=C:\\tmp\\x", out);
    }

    @Test
    void render_invalidPlaceholder_leftAsLiteral() {
        // {{user.name}} contains a dot -> not a valid identifier -> treated as literal text
        String template = "Hello {{user.name}} and {{ok}}";
        String out = PromptTemplate.render(template, Map.of("ok", "World"));
        assertEquals("Hello {{user.name}} and World", out);
    }

    @Test
    void render_singleBrace_leftAsLiteral() {
        String out = PromptTemplate.render("a { not a } placeholder", Collections.emptyMap());
        assertEquals("a { not a } placeholder", out);
    }

    // ==================== extractVariables() ====================

    @Test
    void extractVariables_returnsAllUniqueNames() {
        Set<String> vars = PromptTemplate.extractVariables("{{a}} and {{b}} and {{a}}");
        assertEquals(Set.of("a", "b"), vars);
    }

    @Test
    void extractVariables_templateWithoutVars_returnsEmptySet() {
        assertTrue(PromptTemplate.extractVariables("plain text").isEmpty());
    }

    @Test
    void extractVariables_nullTemplate_returnsEmptySet() {
        assertTrue(PromptTemplate.extractVariables(null).isEmpty());
    }

    @Test
    void extractVariables_emptyTemplate_returnsEmptySet() {
        assertTrue(PromptTemplate.extractVariables("").isEmpty());
    }

    @Test
    void extractVariables_whitespaceInBraces_normalisedName() {
        assertEquals(Set.of("foo"), PromptTemplate.extractVariables("hello {{ foo }} world"));
    }

    @Test
    void extractVariables_invalidNamesIgnored() {
        // {{user.name}} is not a valid identifier -> NOT extracted
        Set<String> vars = PromptTemplate.extractVariables("{{user.name}} {{valid}}");
        assertEquals(Set.of("valid"), vars);
    }

    // ==================== render-edge: same instance returned when no vars ====================

    @Test
    void render_noPlaceholders_returnsSameStringInstance() {
        // Tiny optimisation: the regex matcher.find() short-circuits with no work.
        // We don't strictly require identity, but the algorithm must not throw.
        String template = "static prompt";
        String out = PromptTemplate.render(template, Map.of("unused", "value"));
        assertEquals(template, out);
        // Note: identity is not contractual; only equality matters.
        // (Kept assertSame as a sanity check; remove if implementation changes to always allocate.)
        assertSame(template, out);
    }
}
