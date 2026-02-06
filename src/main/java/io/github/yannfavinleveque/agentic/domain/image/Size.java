package io.github.yannfavinleveque.agentic.domain.image;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Size {

    @JsonProperty("256x256")
    X256("256x256"),

    @JsonProperty("512x512")
    X512("512x512"),

    @JsonProperty("1024x1024")
    X1024("1024x1024"),

    @JsonProperty("1792x1024")
    X1792X("1792x1024"),

    @JsonProperty("1024x1792")
    X1024X("1024x1792");

    private final String value;

    Size(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
