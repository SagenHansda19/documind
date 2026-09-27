package com.ai.documind.controller;

import com.ai.documind.dto.AskRequest;
import com.ai.documind.dto.AskResponse;
import com.ai.documind.dto.ChatMessageDto;
import com.ai.documind.dto.UploadResponse;
import com.ai.documind.service.RagService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
@CrossOrigin(origins = "*") // Allows local development and cross-origin fetch if needed
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    /**
     * 1. Multipart file upload endpoint (.pdf, .txt, .md).
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadDocument(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Please provide a valid, non-empty file."));
        }
        try {
            UploadResponse response = ragService.uploadFile(file);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Upload processing failed: " + e.getMessage()));
        }
    }

    /**
     * 2. Raw text ingestion endpoint (for backward compatibility).
     */
    @PostMapping("/ingest")
    public ResponseEntity<?> ingestDocument(@RequestBody Map<String, String> payload) {
        String text = payload.get("text");
        if (text == null || text.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Text payload cannot be empty"));
        }
        String result = ragService.ingestText(text);
        return ResponseEntity.ok(Map.of("message", result));
    }

    /**
     * 3. RAG Query endpoint with session-based conversational memory.
     */
    @PostMapping("/ask")
    public ResponseEntity<?> askQuestion(@RequestBody AskRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Question cannot be empty"));
        }
        try {
            AskResponse response = ragService.askQuestion(request.getQuestion(), request.getSessionId());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Query execution failed: " + e.getMessage()));
        }
    }

    /**
     * 4. Retrieve conversation history for a session.
     */
    @GetMapping("/chat/{sessionId}/history")
    public ResponseEntity<List<ChatMessageDto>> getHistory(@PathVariable String sessionId) {
        List<ChatMessageDto> history = ragService.getChatHistory(sessionId);
        return ResponseEntity.ok(history);
    }

    /**
     * 5. Reset conversation history for a session.
     */
    @DeleteMapping("/chat/{sessionId}")
    public ResponseEntity<Map<String, String>> clearHistory(@PathVariable String sessionId) {
        ragService.clearChatMemory(sessionId);
        return ResponseEntity.ok(Map.of("message", "Conversation memory cleared for session: " + sessionId));
    }

    /**
     * 6. Purge all document embeddings from the vector store.
     */
    @DeleteMapping("/documents")
    public ResponseEntity<Map<String, String>> clearAllDocuments() {
        ragService.clearAllDocuments();
        return ResponseEntity.ok(Map.of("message", "All document embeddings cleared from vector store."));
    }

    /**
     * 7. Delete embeddings for a specific document file.
     */
    @DeleteMapping("/documents/{fileName}")
    public ResponseEntity<Map<String, String>> deleteDocument(@PathVariable String fileName) {
        boolean deleted = ragService.deleteDocument(fileName);
        return ResponseEntity.ok(Map.of("message", "Document '" + fileName + (deleted ? "' deleted successfully." : "' not found in vector store.")));
    }
}