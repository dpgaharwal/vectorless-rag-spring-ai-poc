package com.vectorlessrag.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

@Service
public class AiConfigService {

    @Getter @Setter
    private String apiKey;

    @Getter @Setter
    private String model = "gpt-4o-mini";

    public OpenAiChatModel buildChatModel() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("API key not configured. Please set it from the UI.");
        }
        OpenAiApi api = OpenAiApi.builder().apiKey(apiKey).build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(0.2d)
                        .build())
                .build();
    }

    public OpenAiEmbeddingModel buildEmbeddingModel() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("API key not configured.");
        }
        OpenAiApi api = OpenAiApi.builder().apiKey(apiKey).build();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model("text-embedding-3-small")
                .build();
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
