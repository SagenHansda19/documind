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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final EmbeddingModel embeddingModel;
    private final ChatLanguageModel chatLanguageModel;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private EmbeddingStore<TextSegment> embeddingStore;

    // In-memory conversation memory keyed by sessionId
    private final Map<String, ChatMemory> chatMemories = new ConcurrentHashMap<>();

    public RagService(EmbeddingModel embeddingModel,
                      ChatLanguageModel chatLanguageModel,
                      JdbcTemplate jdbcTemplate,
                      DataSource dataSource) {
        this.embeddingModel = embeddingModel;
        this.chatLanguageModel = chatLanguageModel;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void init() {
        try {
            this.embeddingStore = PgVectorEmbeddingStore.datasourceBuilder()
                    .datasource(dataSource)
                    .table("doc_embeddings")
                    .dimension(768) // Gemini gemini-embedding-001 vector dimension size
                    .createTable(true)
                    .dropTableFirst(false)
                    .build();
            log.info("PgVectorEmbeddingStore successfully initialized with injected Spring DataSource");
        } catch (Exception e) {
            log.error("Failed to initialize PgVectorEmbeddingStore: {}", e.getMessage(), e);
            throw new RuntimeException("Could not initialize PgVectorEmbeddingStore: " + e.getMessage(), e);
        }
    }

    /**
     * 1. Ingest raw text directly into pgvector with 800-char semantic chunks.
     */
    public String ingestText(String textContent) {
        Document document = Document.from(textContent);
        document.metadata().add("file_name", "raw-text-input");

        // 800-character chunks with 150-character overlap preserve cohesive project/experience blocks
        DocumentSplitter splitter = DocumentSplitters.recursive(800, 150);
        List<TextSegment> segments = splitter.split(document);

        for (TextSegment segment : segments) {
            Embedding embedding = embeddingModel.embed(segment).content();
            embeddingStore.add(embedding, segment);
        }

        return "Successfully ingested " + segments.size() + " text chunks into pgvector!";
    }

    /**
     * 2. Accept and process multipart file upload (.pdf, .txt, .md).
     * Automatically overwrites / purges prior chunks for the same filename to avoid duplicate pollution.
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

        // Split document into overlapping chunks (800 chars with 150 overlap for optimal coherence)
        DocumentSplitter splitter = DocumentSplitters.recursive(800, 150);
        List<TextSegment> segments = splitter.split(document);

        // Deduplicate: purge any prior chunks for this exact file before fresh ingestion
        try {
            int deleted = jdbcTemplate.update("DELETE FROM doc_embeddings WHERE metadata->>'file_name' = ?", originalFilename);
            if (deleted > 0) {
                log.info("Cleared {} existing segments for file '{}' before fresh ingestion", deleted, originalFilename);
            }
        } catch (Exception e) {
            log.warn("Could not delete prior segments for file '{}': {}", originalFilename, e.getMessage());
        }

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
     * Clear all document embeddings from the vector store.
     */
    public void clearAllDocuments() {
        jdbcTemplate.update("TRUNCATE TABLE doc_embeddings");
        log.info("Cleared all document embeddings from pgvector.");
    }

    /**
     * Delete embeddings for a specific document.
     */
    public boolean deleteDocument(String fileName) {
        int deleted = jdbcTemplate.update("DELETE FROM doc_embeddings WHERE metadata->>'file_name' = ?", fileName);
        log.info("Deleted {} segments for file '{}'", deleted, fileName);
        return deleted > 0;
    }

    /**
     * List all distinct uploaded documents and chunk counts in the vector database.
     */
    public List<Map<String, Object>> listDocuments() {
        String sql = "SELECT metadata->>'file_name' AS fileName, " +
                     "MAX(metadata->>'file_size') AS fileSize, " +
                     "COUNT(*) AS chunkCount " +
                     "FROM doc_embeddings " +
                     "WHERE metadata->>'file_name' IS NOT NULL " +
                     "GROUP BY metadata->>'file_name' " +
                     "ORDER BY fileName ASC";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> doc = new HashMap<>();
            doc.put("fileName", rs.getString("fileName"));
            String sizeStr = rs.getString("fileSize");
            long size = 0L;
            if (sizeStr != null && !sizeStr.isBlank()) {
                try {
                    size = Long.parseLong(sizeStr);
                } catch (NumberFormatException ignored) {}
            }
            doc.put("fileSize", size);
            doc.put("chunkCount", rs.getInt("chunkCount"));
            return doc;
        });
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
     * 3. Ask question with conversational memory, vector similarity search, and retrieval deduplication.
     */
    public AskResponse askQuestion(String question, String sessionId) {
        String effectiveSessionId = (sessionId != null && !sessionId.isBlank()) ? sessionId.trim() : "default-session";

        // Embed question and query pgvector with larger search window
        Embedding queryEmbedding = embeddingModel.embed(question).content();

        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(12)
                .minScore(0.35)
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        List<EmbeddingMatch<TextSegment>> rawMatches = searchResult.matches();

        // Deduplicate retrieved matches by text content so duplicate vectors don't crowd out the window
        Set<String> seenTexts = new HashSet<>();
        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : rawMatches) {
            if (match.embedded() != null && match.embedded().text() != null) {
                String clean = match.embedded().text().trim();
                if (seenTexts.add(clean)) {
                    matches.add(match);
                }
            }
        }

        // Cap to top 8 distinct relevant chunks
        if (matches.size() > 8) {
            matches = matches.subList(0, 8);
        }

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
                + "If the user asks about personal details (such as their name, email, contact info), technical skills, education, or projects, "
                + "carefully inspect the document context (including resume headers, profile sections, and project entries) to extract the facts.\n"
                + "Only state 'I cannot find the answer in the provided documents.' if the requested information is genuinely absent from both the context and the conversation history.\n\n"
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
                log.warn("LLM API call attempt {} failed: {}. Retrying...", attempts, e.getMessage());
                if (attempts >= 3) {
                    throw e;
                }
                try {
                    Thread.sleep(1500L * attempts);
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