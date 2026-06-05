package io.github.dpgaharwal.pageindex.util;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.ModelType;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Token counting using jtokkit (tiktoken-compatible).
 * Falls back to char/4 estimate for non-OpenAI models.
 * Mirrors count_tokens() from utils.py.
 */
@Component
public class TokenCounter {

    private final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    private final Encoding cl100kBase;

    public TokenCounter() {
        this.cl100kBase = registry.getEncoding(com.knuddels.jtokkit.api.EncodingType.CL100K_BASE);
    }

    public int count(String text) {
        if (text == null || text.isEmpty()) return 0;
        try {
            return cl100kBase.countTokens(text);
        } catch (Exception e) {
            return text.length() / 4;
        }
    }

    public int count(String text, String modelName) {
        if (text == null || text.isEmpty()) return 0;
        if (modelName == null) return count(text);
        try {
            Optional<ModelType> modelType = ModelType.fromName(modelName);
            if (modelType.isPresent()) {
                Optional<Encoding> enc = registry.getEncodingForModel(modelType.get());
                if (enc.isPresent()) {
                    return enc.get().countTokens(text);
                }
            }
        } catch (Exception ignored) {}
        return count(text);
    }
}
