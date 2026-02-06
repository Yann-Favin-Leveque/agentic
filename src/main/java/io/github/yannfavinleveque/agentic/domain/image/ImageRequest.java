package io.github.yannfavinleveque.agentic.domain.image;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.sashirestela.slimvalidator.constraints.Required;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
@JsonInclude(Include.NON_EMPTY)
public class ImageRequest extends AbstractImageRequest {

    @Required
    private String prompt;

    private Quality quality;

    private Style style;

    public enum Quality {

        // DALL-E 3 quality values
        @JsonProperty("standard")
        STANDARD,

        @JsonProperty("hd")
        HD,

        // gpt-image-1 quality values
        @JsonProperty("low")
        LOW,

        @JsonProperty("medium")
        MEDIUM,

        @JsonProperty("high")
        HIGH,

        @JsonProperty("auto")
        AUTO;

    }

    public enum Style {

        @JsonProperty("vivid")
        VIVID,

        @JsonProperty("natural")
        NATURAL;

    }

}
