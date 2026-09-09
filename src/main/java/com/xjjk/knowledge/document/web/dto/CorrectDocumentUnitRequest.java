package com.xjjk.knowledge.document.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CorrectDocumentUnitRequest(@NotBlank @Size(max = 1_000_000) String correctedText) {}
