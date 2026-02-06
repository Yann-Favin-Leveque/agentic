package io.github.yannfavinleveque.agentic.domain.assistant;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ThreadMessageRole {

    @JsonProperty("user")
    USER,

    @JsonProperty("assistant")
    ASSISTANT;

}
