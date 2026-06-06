package com.vectorlessrag.config;

import com.vectorlessrag.service.AiConfigService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Delegates every ChatModel call to AiConfigService.buildChatModel() so that
 * PageIndexClient (LlmGateway) always uses the API key configured at runtime via the UI,
 * not a key baked into application.yaml at startup.
 */
public class DynamicChatModel implements ChatModel {

    private final AiConfigService aiConfigService;

    public DynamicChatModel(AiConfigService aiConfigService) {
        this.aiConfigService = aiConfigService;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return aiConfigService.buildChatModel().call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return aiConfigService.buildChatModel().stream(prompt);
    }
}
