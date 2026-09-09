package com.xjjk.knowledge.document.processing;

import org.springframework.stereotype.Component;

/** 中文按接近一字一 Token、英文按约四字符一 Token，预算留有安全余量。 */
@Component
public class ConservativeTokenEstimator implements TokenEstimator {
    @Override
    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                cjk++;
            } else if (!Character.isWhitespace(codePoint)) {
                other++;
            }
            offset += Character.charCount(codePoint);
        }
        return cjk + (int) Math.ceil(other / 4.0);
    }
}
