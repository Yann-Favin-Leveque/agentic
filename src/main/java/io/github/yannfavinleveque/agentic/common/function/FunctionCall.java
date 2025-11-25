package io.github.yannfavinleveque.agentic.common.function;

import io.github.sashirestela.slimvalidator.constraints.Required;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class FunctionCall {

    @Required
    private String name;

    @Required
    private String arguments;

}
