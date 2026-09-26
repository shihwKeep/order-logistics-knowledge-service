package com.xjjk.knowledge.publication.release.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record CreateReleaseRequest(
        @NotEmpty List<@Valid ReplacementRequest> replacements) {

    public record ReplacementRequest(
            @Positive long documentId,
            @Positive long versionId) {
    }
}
