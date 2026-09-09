package com.xjjk.knowledge.document.ocr;

import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Component;

/** PaddleOCR HTTP 客户端。日志不得输出 imageBase64 或识别正文。 */
@Component
public class PaddleOcrHttpClient implements OcrClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final java.time.Duration readTimeout;
    private final int maxResponseBytes;
    private final double lowConfidenceThreshold;

    public PaddleOcrHttpClient(OcrProperties properties) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        this.objectMapper = new ObjectMapper();
        this.endpoint = URI.create(properties.getBaseUrl() + "/v1/ocr");
        this.readTimeout = properties.getReadTimeout();
        this.maxResponseBytes = properties.getMaxResponseBytes();
        this.lowConfidenceThreshold = properties.getLowConfidenceThreshold();
    }

    @Override
    public OcrResult recognize(String requestId, String language, byte[] image) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(
                    new OcrRequest(requestId, language, Base64.getEncoder().encodeToString(image)));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(readTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<byte[]> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (httpResponse.statusCode() / 100 != 2 || httpResponse.body().length > maxResponseBytes) {
                throw new BusinessException(ApiErrorCode.DOCUMENT_OCR_FAILED);
            }
            OcrResponse response = objectMapper.readValue(httpResponse.body(), OcrResponse.class);
            if (response == null || response.blocks() == null) {
                throw new BusinessException(ApiErrorCode.DOCUMENT_OCR_FAILED);
            }
            List<OcrBlock> blocks = response.blocks().stream()
                    .map(block -> new OcrBlock(
                            block.text(), block.confidence(), block.box(),
                            block.confidence() < lowConfidenceThreshold))
                    .toList();
            return new OcrResult(response.requestId(), response.rotation(), blocks);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new BusinessException(ApiErrorCode.DOCUMENT_OCR_FAILED, exception);
        }
    }

    private record OcrRequest(String requestId, String language, String imageBase64) {}
    private record OcrResponse(String requestId, int rotation, List<OcrResponseBlock> blocks) {}
    private record OcrResponseBlock(String text, double confidence, List<List<Integer>> box) {}
}
