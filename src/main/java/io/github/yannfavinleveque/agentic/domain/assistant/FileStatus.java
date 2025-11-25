package io.github.yannfavinleveque.agentic.domain.assistant;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum FileStatus {

    @JsonProperty("in_progress")
    IN_PROGRESS,

    @JsonProperty("completed")
    COMPLETED,

    @JsonProperty("cancelled")
    CANCELLED,

    @JsonProperty("failed")
    FAILED;

}
