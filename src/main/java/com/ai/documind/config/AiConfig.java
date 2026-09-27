package com.ai.documind.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.chat.model:gemini-3.8-flash}")
    private String chatModelName;

    @Value("${gemini.embedding.model:gemini-embedding-001}")
    private String embeddingModelName;

    @Value("${gemini.embedding.dimension:768}")
    private int embeddingDimension;

    /**
     * Normalizes the API key:
     * - Trims surrounding whitespace and quotes.
     * - Restores the 'AQ.' prefix if it was omitted during copy-paste.
     */
    private String resolveApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        String key = apiKey.trim().replace("\"", "").replace("'", "");
        if (!key.startsWith("AQ.") && !key.startsWith("AIza")) {
            key = "AQ." + key;
        }
        return key;
    }

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(resolveApiKey())
                .modelName(chatModelName)
                .temperature(0.2)
                .maxRetries(3)
                .timeout(java.time.Duration.ofSeconds(60))
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return GoogleAiEmbeddingModel.builder()
                .apiKey(resolveApiKey())
                .modelName(embeddingModelName)
                .outputDimensionality(embeddingDimension)
                .timeout(java.time.Duration.ofSeconds(60))
                .build();
    }
}