package io.github.yannfavinleveque.agentic.common.function;

import io.github.yannfavinleveque.agentic.support.JsonSchemaUtil;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;

@Getter
@Builder
public class FunctionDef {

    @NonNull
    private String name;

    private String description;

    @NonNull
    private Class<? extends Functional> functionalClass;

    private Boolean strict;

    @Builder.Default
    private SchemaConverter schemaConverter = JsonSchemaUtil.defaultConverter;

}
