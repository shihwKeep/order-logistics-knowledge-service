package com.xjjk.knowledge.document.web;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.api.ApiResponse;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.document.service.DocumentUploadService;
import com.xjjk.knowledge.document.service.DocumentQueryService;
import com.xjjk.knowledge.document.service.DocumentCorrectionService;
import com.xjjk.knowledge.document.web.dto.CorrectDocumentUnitRequest;
import com.xjjk.knowledge.document.web.dto.DocumentDetailResponse;
import com.xjjk.knowledge.document.web.dto.DocumentChunkResponse;
import com.xjjk.knowledge.document.web.dto.DocumentResponse;
import com.xjjk.knowledge.document.web.dto.DocumentUnitResponse;
import com.xjjk.knowledge.document.web.dto.DocumentVersionResponse;
import com.xjjk.knowledge.document.web.dto.IngestionTaskResponse;
import com.xjjk.knowledge.publication.PublicationRecord;
import com.xjjk.knowledge.publication.PublicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;
import java.util.List;

/** 租户路径显式化的文档管理入口。 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents")
public class DocumentController {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    private final DocumentUploadService uploadService;
    private final DocumentQueryService queryService;
    private final DocumentCorrectionService correctionService;
    private final PublicationService publicationService;

    public DocumentController(DocumentUploadService uploadService) {
        this(uploadService, null, null, null);
    }

    public DocumentController(
            DocumentUploadService uploadService,
            DocumentQueryService queryService,
            DocumentCorrectionService correctionService) {
        this(uploadService, queryService, correctionService, null);
    }

    @Autowired
    public DocumentController(
            DocumentUploadService uploadService,
            DocumentQueryService queryService,
            DocumentCorrectionService correctionService,
            PublicationService publicationService) {
        this.uploadService = uploadService;
        this.queryService = queryService;
        this.correctionService = correctionService;
        this.publicationService = publicationService;
    }

    @GetMapping
    public ApiResponse<List<DocumentDetailResponse>> list(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        prepareRequestId(request, response);
        AdminPrincipal principal = principal(authentication);
        return ApiResponse.success(queryService.list(principal, tenantId, knowledgeBaseId)
                .stream()
                .map(document -> DocumentDetailResponse.from(
                        document,
                        queryService.versions(
                                principal, tenantId, knowledgeBaseId, document.id())))
                .toList());
    }

    @GetMapping("/{documentId}")
    public ApiResponse<DocumentDetailResponse> detail(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            @PathVariable @Positive long documentId,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        prepareRequestId(request, response);
        AdminPrincipal principal = principal(authentication);
        return ApiResponse.success(DocumentDetailResponse.from(
                queryService.document(principal, tenantId, knowledgeBaseId, documentId),
                queryService.versions(principal, tenantId, knowledgeBaseId, documentId)));
    }

    @GetMapping("/{documentId}/versions")
    public ApiResponse<List<DocumentVersionResponse>> versions(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            @PathVariable @Positive long documentId,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        prepareRequestId(request, response);
        return ApiResponse.success(queryService.versions(
                        principal(authentication), tenantId, knowledgeBaseId, documentId)
                .stream().map(DocumentVersionResponse::from).toList());
    }

    @GetMapping("/{documentId}/versions/{versionId}/units")
    public ApiResponse<List<DocumentUnitResponse>> units(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            @PathVariable @Positive long documentId,
            @PathVariable @Positive long versionId,
            @RequestParam(value = "lowConfidence", required = false) Boolean lowConfidence,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        prepareRequestId(request, response);
        return ApiResponse.success(queryService.units(
                        principal(authentication), tenantId, knowledgeBaseId, documentId, versionId,
                        lowConfidence, offset, limit)
                .stream().map(DocumentUnitResponse::from).toList());
    }

    @GetMapping("/{documentId}/versions/{versionId}/chunks")
    public ApiResponse<List<DocumentChunkResponse>> chunks(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            @PathVariable @Positive long documentId,
            @PathVariable @Positive long versionId,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        prepareRequestId(request, response);
        return ApiResponse.success(queryService.chunks(
                        principal(authentication), tenantId, knowledgeBaseId, documentId, versionId,
                        offset, limit)
                .stream().map(DocumentChunkResponse::from).toList());
    }

    @GetMapping("/{documentId}/versions/{versionId}/task")
    public ApiResponse<IngestionTaskResponse> task(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            @PathVariable @Positive long documentId,
            @PathVariable @Positive long versionId,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        prepareRequestId(request, response);
        return ApiResponse.success(queryService.latestTask(
                        principal(authentication), tenantId, knowledgeBaseId, documentId, versionId)
                .map(IngestionTaskResponse::from).orElse(null));
    }

    @PutMapping("/{documentId}/versions/{versionId}/units/{unitId}/correction")
    public ApiResponse<DocumentUnitResponse> correct(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            @PathVariable @Positive long documentId,
            @PathVariable @Positive long versionId,
            @PathVariable @Positive long unitId,
            @Valid @RequestBody CorrectDocumentUnitRequest body,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        String requestId = prepareRequestId(request, response);
        return ApiResponse.success(DocumentUnitResponse.from(correctionService.correct(
                principal(authentication), tenantId, knowledgeBaseId, documentId, versionId,
                unitId, body.correctedText(), requestId)));
    }

    @PostMapping("/{documentId}/versions/{versionId}/retry")
    public ApiResponse<Boolean> retry(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            @PathVariable @Positive long documentId,
            @PathVariable @Positive long versionId,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        String requestId = prepareRequestId(request, response);
        return ApiResponse.success(correctionService.retry(
                principal(authentication), tenantId, knowledgeBaseId, documentId, versionId, requestId));
    }

    @PostMapping(consumes = "multipart/form-data")
    public ApiResponse<DocumentResponse> upload(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        String requestId = prepareRequestId(request, response);
        try {
            return ApiResponse.success(DocumentResponse.from(uploadService.uploadNewDocument(
                    principal(authentication),
                    tenantId,
                    knowledgeBaseId,
                    title,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getBytes(),
                    requestId)));
        } catch (IOException exception) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_STORAGE_UNAVAILABLE, exception);
        }
    }

    @PostMapping(value = "/{documentId}/versions", consumes = "multipart/form-data")
    public ApiResponse<DocumentResponse> uploadVersion(
            @PathVariable @Positive long tenantId,
            @PathVariable @Positive long knowledgeBaseId,
            @PathVariable @Positive long documentId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        String requestId = prepareRequestId(request, response);
        try {
            return ApiResponse.success(DocumentResponse.from(uploadService.uploadNewVersion(
                    principal(authentication), tenantId, knowledgeBaseId, documentId,
                    file.getOriginalFilename(), file.getContentType(), file.getBytes(), requestId)));
        } catch (IOException exception) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_STORAGE_UNAVAILABLE, exception);
        }
    }

    @PostMapping("/{documentId}/versions/{versionId}/publish")
    public ApiResponse<PublicationRecord> publish(
            @PathVariable @Positive long tenantId, @PathVariable @Positive long knowledgeBaseId,
            @PathVariable @Positive long documentId, @PathVariable @Positive long versionId,
            Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        String requestId = prepareRequestId(request, response);
        return ApiResponse.success(publicationService.publish(
                principal(authentication), tenantId, knowledgeBaseId, documentId, versionId, requestId));
    }

    @PostMapping("/{documentId}/versions/{versionId}/rollback")
    public ApiResponse<PublicationRecord> rollback(
            @PathVariable @Positive long tenantId, @PathVariable @Positive long knowledgeBaseId,
            @PathVariable @Positive long documentId, @PathVariable @Positive long versionId,
            Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        String requestId = prepareRequestId(request, response);
        return ApiResponse.success(publicationService.rollback(
                principal(authentication), tenantId, knowledgeBaseId, documentId, versionId, requestId));
    }

    @PostMapping("/{documentId}/disable")
    public ApiResponse<PublicationRecord> disable(
            @PathVariable @Positive long tenantId, @PathVariable @Positive long knowledgeBaseId,
            @PathVariable @Positive long documentId,
            Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        String requestId = prepareRequestId(request, response);
        return ApiResponse.success(publicationService.disable(
                principal(authentication), tenantId, knowledgeBaseId, documentId, requestId));
    }

    private AdminPrincipal principal(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AdminPrincipal principal)) {
            throw new BusinessException(ApiErrorCode.AUTH_REQUIRED);
        }
        return principal;
    }

    private String prepareRequestId(HttpServletRequest request, HttpServletResponse response) {
        String supplied = request.getHeader(REQUEST_ID_HEADER);
        String requestId = supplied == null
                || supplied.isBlank()
                || supplied.length() > MAX_REQUEST_ID_LENGTH
                ? UUID.randomUUID().toString()
                : supplied;
        request.setAttribute("requestId", requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        return requestId;
    }
}
