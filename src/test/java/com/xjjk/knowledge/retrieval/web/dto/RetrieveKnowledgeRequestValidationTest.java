package com.xjjk.knowledge.retrieval.web.dto;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class RetrieveKnowledgeRequestValidationTest {

    @Test
    void limitsKnowledgeBaseScopeToFiftyIds() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new RetrieveKnowledgeRequest(
                    "退款规则是什么",
                    LongStream.rangeClosed(1, 51).boxed().toList());

            assertThat(factory.getValidator().validate(request))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("knowledgeBaseIds");
        }
    }
}
