package com.vectorlessrag.config;

import com.vectorlessrag.service.AiConfigService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AiConfig {

  @Bean
  @Primary
  public ChatModel primaryChatModel(AiConfigService aiConfigService) {
    return new DynamicChatModel(aiConfigService);
  }

  @Bean
  public ChatClient chatClient(
          @Qualifier("ollamaChatModel") ChatModel chatModel) {
    return ChatClient.builder(chatModel)
        .defaultSystem("You are a precise document analysis assistant.")
        .build();
  }

  @Bean
  @Primary
  public EmbeddingModel embeddingModel(
          @Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel) {
    return embeddingModel;
  }
}