package io.github.yannfavinleveque.agentic.common.content;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ImageDetail {

    @JsonProperty("auto")
    AUTO,

    @JsonProperty("low")
    LOW,

    @JsonProperty("high")
    HIGH;

}
