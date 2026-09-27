package com.ai.documind.service;

import com.ai.documind.dto.AskResponse;
import com.ai.documind.dto.ChatMessageDto;
import com.ai.documind.dto.SourceCitation;
import com.ai.documind.dto.UploadResponse;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final EmbeddingModel embeddingModel;
    private final ChatLanguageModel chatLanguageModel;
    private EmbeddingStore<TextSegment> embeddingStore;

    // In-memory conversation memory keyed by sessionId
    private final Map<String, ChatMemory> chatMemories = new ConcurrentHashMap<>();

    @Value("${spring.datasource.username}")
    private String dbUser;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    public RagService(EmbeddingModel embeddingModel, ChatLanguageModel chatLanguageModel) {
        this.embeddingModel = embeddingModel;
        this.chatLanguageModel = chatLanguageModel;
    }

    @PostConstruct
    public void init() {
        this.embeddingStore = PgVectorEmbeddingStore.builder()
                .host("localhost")
                .port(5432)
                .database("documind_db")
                .user(dbUser)
                .password(dbPassword)
                .table("doc_embeddings")
                .dimension(768) // Gemini gemini-embedding-001 vector dimension size
                .createTable(true)
                .dropTableFirst(false)
                .build();
    }

    /**
     * 1. Ingest raw text directly into pgvector.
     */
    public String ingestText(String textContent) {
        Document document = Document.from(textContent);
        document.metadata().add("file_name", "raw-text-input");

        DocumentSplitter splitter = DocumentSplitters.recursive(300, 50);
        List<TextSegment> segments = splitter.split(document);

        for (TextSegment segment : segments) {
            Embedding embedding = embeddingModel.embed(segment).content();
            embeddingStore.add(embedding, segment);
        }

        return "Successfully ingested " + segments.size() + " text chunks into pgvector!";
    }

    /**
     * 2. Accept and process multipart file upload (.pdf, .txt, .md).
     */
    public UploadResponse uploadFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Upload rejected: File is empty or missing.");
        }

        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.txt";
        String lowerName = originalFilename.toLowerCase();

        DocumentParser parser;
        if (lowerName.endsWith(".pdf")) {
            parser = new ApachePdfBoxDocumentParser();
        } else if (lowerName.endsWith(".txt") || lowerName.endsWith(".md") || lowerName.endsWith(".csv") || lowerName.endsWith(".json")) {
            parser = new TextDocumentParser(StandardCharsets.UTF_8);
        } else {
            throw new IllegalArgumentException("Unsupported file type for '" + originalFilename + "'. Supported formats: .pdf, .txt, .md, .csv, .json");
        }

        Document document;
        try (InputStream inputStream = file.getInputStream()) {
            document = parser.parse(inputStream);
        } catch (Exception e) {
            log.error("Failed to parse file: {}", originalFilename, e);
            throw new RuntimeException("Error parsing document '" + originalFilename + "': " + e.getMessage(), e);
        }

        if (document.text() == null || document.text().trim().isEmpty()) {
            throw new IllegalArgumentException("Uploaded document contains no extractable text content.");
        }

        // Attach file metadata
        document.metadata().add("file_name", originalFilename);
        document.metadata().add("file_size", String.valueOf(file.getSize()));

        // Split document into overlapping chunks
        DocumentSplitter splitter = DocumentSplitters.recursive(300, 50);
        List<TextSegment> segments = splitter.split(document);

        // Embed and persist to pgvector
        for (TextSegment segment : segments) {
            Embedding embedding = embeddingModel.embed(segment).content();
            embeddingStore.add(embedding, segment);
        }

        log.info("Successfully ingested {} segments from file '{}'", segments.size(), originalFilename);
        return new UploadResponse(
                originalFilename,
                file.getSize(),
                segments.size(),
                "Successfully parsed and stored " + segments.size() + " segments into pgvector.",
                true
        );
    }

    /**
     * Retrieve or create conversation memory for a session.
     */
    public ChatMemory getOrCreateChatMemory(String sessionId) {
        String key = (sessionId != null && !sessionId.isBlank()) ? sessionId.trim() : "default-session";
        return chatMemories.computeIfAbsent(key, id ->
                MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(20) // Keep up to 10 conversation turns
                        .build()
        );
    }

    /**
     * Clear conversation history for a given session.
     */
    public void clearChatMemory(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            ChatMemory memory = chatMemories.get(sessionId.trim());
            if (memory != null) {
                memory.clear();
            }
        }
    }

    /**
     * Export conversation messages for a session.
     */
    public List<ChatMessageDto> getChatHistory(String sessionId) {
        ChatMemory memory = getOrCreateChatMemory(sessionId);
        return memory.messages().stream().map(msg -> {
            String role;
            if (msg instanceof UserMessage) {
                role = "user";
            } else if (msg instanceof AiMessage) {
                role = "assistant";
            } else {
                role = "system";
            }
            return new ChatMessageDto(role, msg.text());
        }).collect(Collectors.toList());
    }

    /**
     * 3. Ask question with conversational memory and vector similarity search.
     */
    public AskResponse askQuestion(String question, String sessionId) {
        String effectiveSessionId = (sessionId != null && !sessionId.isBlank()) ? sessionId.trim() : "default-session";

        // Embed question and query pgvector
        Embedding queryEmbedding = embeddingModel.embed(question).content();

        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(4)
                .minScore(0.5)
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();

        List<SourceCitation> sources = matches.stream()
                .map(match -> {
                    String fn = match.embedded().metadata().getString("file_name");
                    return new SourceCitation(fn, match.score(), match.embedded().text());
                })
                .collect(Collectors.toList());

        String context = matches.stream()
                .map(match -> {
                    String fn = match.embedded().metadata().getString("file_name");
                    String sourceLabel = (fn != null) ? "[Document: " + fn + "]\n" : "";
                    return sourceLabel + match.embedded().text();
                })
                .collect(Collectors.joining("\n---\n"));

        String systemPrompt = "You are DocuMind, an enterprise AI knowledge assistant.\n"
                + "Answer the user's question accurately, concisely, and helpfully using the provided document context below.\n"
                + "You must maintain conversational continuity using the chat history.\n"
                + "If the answer cannot be found in the provided document context or conversation history, state clearly: "
                + "'I cannot find the answer in the provided documents.'\n\n"
                + "--- Document Context ---\n"
                + (context.isBlank() ? "No matching document context found." : context);

        ChatMemory chatMemory = getOrCreateChatMemory(effectiveSessionId);

        // Build message payload: SystemInstruction + History + Current Query
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.addAll(chatMemory.messages());
        messages.add(UserMessage.from(question));

        Response<AiMessage> response = null;
        int attempts = 0;
        while (attempts < 3) {
            try {
                attempts++;
                response = chatLanguageModel.generate(messages);
                break;
            } catch (Exception e) {
                log.warn("Gemini API call attempt {} failed: {}. Retrying...", attempts, e.getMessage());
                if (attempts >= 3) {
                    throw e;
                }
                try {
                    Thread.sleep(2500L * attempts);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
        }
        String answer = response != null && response.content() != null ? response.content().text() : "No response generated.";

        // Record turn in chat memory
        chatMemory.add(UserMessage.from(question));
        chatMemory.add(AiMessage.from(answer));

        return new AskResponse(answer, effectiveSessionId, sources);
    }

    /**
     * Backward-compatible askQuestion without sessionId parameter.
     */
    public String askQuestion(String question) {
        return askQuestion(question, "default-session").getAnswer();
    }
}