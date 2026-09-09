package com.xjjk.knowledge.document.parser;

import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;

/** 统一路由格式专用解析器，格式选择只依赖已通过上传校验的元数据。 */
@Component
public class DocumentParserRegistry {
    private final List<DocumentParser> parsers;

    public DocumentParserRegistry(List<DocumentParser> parsers) {
        this.parsers = List.copyOf(parsers);
    }

    public DocumentParser select(String extension, String mimeType) {
        return parsers.stream()
                .filter(parser -> parser.supports(extension, mimeType))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ApiErrorCode.DOCUMENT_UNSUPPORTED_TYPE));
    }
}
