package com.ai.documind.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Value("${ai.provider:auto}")
    private String aiProvider;

    @Value("${gemini.api.key:}")
    private String geminiApiKey;

    @Value("${gemini.chat.model:gemini-3.8-flash}")
    private String geminiChatModel;

    @Value("${gemini.embedding.model:gemini-embedding-001}")
    private String embeddingModelName;

    @Value("${gemini.embedding.dimension:768}")
    private int embeddingDimension;

    @Value("${groq.api.key:}")
    private String groqApiKey;

    @Value("${groq.base.url:https://api.groq.com/openai/v1}")
    private String groqBaseUrl;

    @Value("${groq.chat.model:openai/gpt-oss-120b}")
    private String groqChatModel;

    /**
     * Resolves Gemini API key with triple fallback:
     * 1. Spring property (${gemini.api.key})
     * 2. OS environment variable (GEMINI_API_KEY)
     * 3. Local .env file
     */
    private String resolveGeminiApiKey() {
        String key = geminiApiKey;
        if (key == null || key.isBlank()) {
            key = System.getenv("GEMINI_API_KEY");
        }
        if (key == null || key.isBlank()) {
            key = extractFromEnvFile("GEMINI_API_KEY");
        }
        if (key != null && !key.isBlank()) {
            key = key.trim().replace("\"", "").replace("'", "");
            if (!key.startsWith("AQ.") && !key.startsWith("AIza")) {
                key = "AQ." + key;
            }
        }
        return key;
    }

    /**
     * Resolves Groq API key with triple fallback:
     * 1. Spring property (${groq.api.key})
     * 2. OS environment variable (GROQ_API_KEY)
     * 3. Local .env file
     */
    private String resolveGroqApiKey() {
        String key = groqApiKey;
        if (key == null || key.isBlank()) {
            key = System.getenv("GROQ_API_KEY");
        }
        if (key == null || key.isBlank()) {
            key = extractFromEnvFile("GROQ_API_KEY");
        }
        if (key != null) {
            key = key.trim().replace("\"", "").replace("'", "");
        }
        return key;
    }

    private String extractFromEnvFile(String variableName) {
        try {
            Path envPath = Path.of(".env");
            if (Files.exists(envPath)) {
                for (String line : Files.readAllLines(envPath)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith(variableName + "=")) {
                        return trimmed.substring((variableName + "=").length()).trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private ChatLanguageModel buildGroqChatModel(String key) {
        return OpenAiChatModel.builder()
                .baseUrl(groqBaseUrl)
                .apiKey(key)
                .modelName(groqChatModel)
                .temperature(0.3)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(3)
                .build();
    }

    private ChatLanguageModel buildGeminiChatModel(String key) {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(key)
                .modelName(geminiChatModel)
                .temperature(0.2)
                .maxRetries(3)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        String provider = (aiProvider != null) ? aiProvider.trim().toLowerCase() : "auto";
        String groqKey = resolveGroqApiKey();
        String geminiKey = resolveGeminiApiKey();

        boolean hasGroq = groqKey != null && !groqKey.isBlank();
        boolean hasGemini = geminiKey != null && !geminiKey.isBlank();

        if ("groq".equalsIgnoreCase(provider)) {
            if (!hasGroq) {
                throw new IllegalStateException("ai.provider=groq but GROQ_API_KEY is missing from environment and .env");
            }
            log.info("Initialized ChatLanguageModel with Groq Cloud LPU ({})", groqChatModel);
            return buildGroqChatModel(groqKey);
        }

        if ("gemini".equalsIgnoreCase(provider)) {
            if (!hasGemini) {
                throw new IllegalStateException("ai.provider=gemini but GEMINI_API_KEY is missing from environment and .env");
            }
            log.info("Initialized ChatLanguageModel with Google Gemini ({})", geminiChatModel);
            return buildGeminiChatModel(geminiKey);
        }

        // 'auto' mode: Failover support if both keys exist, or select available provider
        if (hasGroq && hasGemini) {
            log.info("Initialized Resilient Dual-Provider Chat Model (Primary: Groq [{}], Failover: Gemini [{}])",
                    groqChatModel, geminiChatModel);
            ChatLanguageModel primary = buildGroqChatModel(groqKey);
            ChatLanguageModel secondary = buildGeminiChatModel(geminiKey);
            return new ResilientFailoverChatModel(primary, secondary);
        } else if (hasGroq) {
            log.info("Initialized ChatLanguageModel with Groq Cloud ({})", groqChatModel);
            return buildGroqChatModel(groqKey);
        } else if (hasGemini) {
            log.info("Initialized ChatLanguageModel with Google Gemini ({})", geminiChatModel);
            return buildGeminiChatModel(geminiKey);
        } else {
            throw new IllegalStateException("No AI provider key found! Please define either GROQ_API_KEY or GEMINI_API_KEY in .env or environment.");
        }
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        String geminiKey = resolveGeminiApiKey();
        if (geminiKey == null || geminiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is required for the 768-d vector embedding model! Please set it in .env or environment.");
        }

        return GoogleAiEmbeddingModel.builder()
                .apiKey(geminiKey)
                .modelName(embeddingModelName)
                .outputDimensionality(embeddingDimension)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /**
     * Resilient failover chat language model:
     * Attempts the primary low-latency model (Groq), and automatically falls back to secondary (Gemini)
     * if rate limits (429), high demand spikes (503), or network timeouts occur.
     */
    private static class ResilientFailoverChatModel implements ChatLanguageModel {
        private final ChatLanguageModel primary;
        private final ChatLanguageModel secondary;

        public ResilientFailoverChatModel(ChatLanguageModel primary, ChatLanguageModel secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }

        @Override
        public String generate(String userMessage) {
            try {
                return primary.generate(userMessage);
            } catch (Exception e) {
                log.warn("Primary LLM provider failed: {}. Falling back to secondary provider...", e.getMessage());
                return secondary.generate(userMessage);
            }
        }

        @Override
        public Response<AiMessage> generate(ChatMessage... messages) {
            try {
                return primary.generate(messages);
            } catch (Exception e) {
                log.warn("Primary LLM provider failed: {}. Falling back to secondary provider...", e.getMessage());
                return secondary.generate(messages);
            }
        }

        @Override
        public Response<AiMessage> generate(List<ChatMessage> messages) {
            try {
                return primary.generate(messages);
            } catch (Exception e) {
                log.warn("Primary LLM provider failed: {}. Falling back to secondary provider...", e.getMessage());
                return secondary.generate(messages);
            }
        }
    }
}