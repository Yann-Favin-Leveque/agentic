package io.github.yannfavinleveque.agentic.domain.assistant;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum RunStepType {

    @JsonProperty("message_creation")
    MESSAGE_CREATION,

    @JsonProperty("tool_calls")
    TOOL_CALLS;

}
