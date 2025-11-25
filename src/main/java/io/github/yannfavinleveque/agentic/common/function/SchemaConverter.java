package io.github.yannfavinleveque.agentic.common.function;

import com.fasterxml.jackson.databind.JsonNode;

public interface SchemaConverter {

    JsonNode convert(Class<?> c);

}
