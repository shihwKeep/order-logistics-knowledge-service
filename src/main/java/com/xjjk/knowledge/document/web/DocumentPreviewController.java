package com.xjjk.knowledge.document.web;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.document.service.DocumentPreview;
import com.xjjk.knowledge.document.service.DocumentPreviewService;
import jakarta.validation.constraints.Positive;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@Validated
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents")
public class DocumentPreviewController {
    private final DocumentPreviewService service;

    public DocumentPreviewController(DocumentPreviewService service) {
        this.service = service;
    }

    @GetMapping("/{documentId}/versions/{versionId}/source")
    public ResponseEntity<InputStreamResource> source(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            @PathVariable @Positive long documentId,
            @PathVariable @Positive long versionId,
            Authentication authentication) {
        DocumentPreview preview = service.source(
                principal(authentication), tenantId, knowledgeBaseId, documentId, versionId);
        ContentDisposition disposition = (preview.inline()
                ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(preview.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .contentType(MediaType.parseMediaType(preview.contentType()))
                .contentLength(preview.contentLength())
                .body(new InputStreamResource(preview.content()));
    }

    private AdminPrincipal principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AdminPrincipal principal)) {
            throw new BusinessException(ApiErrorCode.AUTH_REQUIRED);
        }
        return principal;
    }
}
