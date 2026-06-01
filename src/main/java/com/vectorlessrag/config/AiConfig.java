package com.vectorlessrag.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AiConfig {

  @Value("${spring.ai.openai.api-key}")
  private String openAiApiKey;

  @Bean
  @Primary
  public OpenAiChatModel openAiChatModel() {
    OpenAiApi openAiApi = OpenAiApi.builder()
        .apiKey(openAiApiKey)
        .build();

    OpenAiChatOptions options = OpenAiChatOptions.builder()
        .model("gpt-4o-mini")
        .temperature(0.2)
        .build();

    return OpenAiChatModel.builder()
        .openAiApi(openAiApi)
        .defaultOptions(options)
        .build();
  }

  @Bean
  public ChatClient chatClient(
      @Qualifier("openAiChatModel") OpenAiChatModel chatModel) {
    return ChatClient.builder(chatModel)
        .defaultSystem("You are a precise document analysis assistant.")
        .build();
  }

  @Bean
  @Primary
  public EmbeddingModel embeddingModel(
      @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel) {
    return embeddingModel;
  }
}