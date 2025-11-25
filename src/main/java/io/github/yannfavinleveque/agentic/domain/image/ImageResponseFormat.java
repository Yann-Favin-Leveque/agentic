package io.github.yannfavinleveque.agentic.domain.image;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ImageResponseFormat {

    @JsonProperty("url")
    URL,

    @JsonProperty("b64_json")
    B64JSON;

}
