package io.github.yannfavinleveque.agentic.support;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.yannfavinleveque.agentic.common.function.SchemaConverter;

public class JsonSchemaUtil {

    public static final SchemaConverter defaultConverter = new DefaultSchemaConverter();

    public static final String JSON_EMPTY_CLASS = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

    private JsonSchemaUtil() {
    }

    public static JsonNode classToJsonSchema(Class<?> clazz) {
        return defaultConverter.convert(clazz);
    }

}
