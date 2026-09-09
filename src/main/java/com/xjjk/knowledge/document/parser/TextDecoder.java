package com.xjjk.knowledge.document.parser;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class TextDecoder {
    private TextDecoder() {
    }

    static String decode(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException ignored) {
            return Charset.forName("GB18030").decode(ByteBuffer.wrap(content)).toString();
        }
    }

    static String normalizeInline(String value) {
        return value == null ? "" : value.replace('\u0000', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
