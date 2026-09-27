# DocuMind Enterprise Architecture & Technical Reference

DocuMind is an enterprise-grade Retrieval-Augmented Generation (RAG) knowledge assistant built using **Spring Boot 3.3.0**, **PostgreSQL with pgvector**, and **Google Gemini** orchestrated via **LangChain4j 0.35.0**.

---

## 1. System Architecture Overview

DocuMind decouples document chunking and vector storage from the retrieval and generative inference pipeline. This architecture provides enterprise knowledge retrieval with strict isolation between client queries and model hallucinations.

```
+---------------------------------------------------------------------------------------------------+
|                                  Spring Boot 3.3.0 Application                                    |
|                                                                                                   |
|  [REST Client] ---> [ RagController ] ---> [ RagService ] <------- [ AiConfig ]                  |
|       HTTP              (:8080)                 | (Core RAG Engine)   (Beans & Credentials)       |
|                                                / \                                                |
+-----------------------------------------------+---+-----------------------------------------------+
                                               /     \
                                              /       \
                     (Store / Search Vector) /         \ (Vectorize / Generate)
                                            v           v
                    +---------------------------+   +------------------------------+
                    |        PostgreSQL         |   |       Google AI Studio       |
                    |         (pgvector)        |   |           (Gemini)           |
                    |                           |   |                              |
                    | • Database: documind_db   |   | • Chat: gemini-3.8-flash     |
                    | • Table: doc_embeddings   |   |   (or gemini-1.5-flash)      |
                    | • Dimension: 768          |   | • Embed: gemini-embedding-001|
                    | • Cosine similarity index |   |   (or text-embedding-004)    |
                    +---------------------------+   +------------------------------+
```

### Visual Architecture Diagram
An interactive standalone HTML architecture diagram has been generated using Archify:
- **Interactive File**: [`documind-architecture.html`](file:///home/sagen/Projects/documind/docs/documind-architecture.html)
- Features: Pan/zoom, dark/light theme toggle, semantic view focus, and relationship tracing.

---

## 2. Component Breakdown

### 2.1 [AiConfig](file:///home/sagen/Projects/documind/src/main/java/com/ai/documind/config/AiConfig.java)
- **Role**: Spring Configuration bean factory.
- **Responsibilities**:
  - Injects `gemini.api.key`, `gemini.chat.model`, `gemini.embedding.model`, and `gemini.embedding.dimension` from `application.properties` / environment.
  - Builds `ChatLanguageModel` (`GoogleAiGeminiChatModel`) pointing to Gemini Flash.
  - Builds `EmbeddingModel` (`GoogleAiEmbeddingModel`) with explicit `outputDimensionality(768)` to guarantee vector dimension parity with pgvector.

### 2.2 [RagService](file:///home/sagen/Projects/documind/src/main/java/com/ai/documind/service/RagService.java)
- **Role**: Core RAG orchestration service.
- **Lifecycle & Setup (`@PostConstruct init`)**:
  - Initializes `PgVectorEmbeddingStore` connected to PostgreSQL (`localhost:5432/documind_db`).
  - Sets schema parameters: table `doc_embeddings`, vector dimension `768`, and automatic table bootstrapping.
- **Methods**:
  - `ingestText(String textContent)`: Splits text into overlapping segments via `DocumentSplitters.recursive(300, 50)`, converts segments into 768-d embeddings using `embeddingModel.embed(segment)`, and commits them into `doc_embeddings`.
  - `askQuestion(String question)`: Embeds the user question into a 768-d query vector, conducts a cosine-similarity search in `PgVectorEmbeddingStore` (filtering `topK=3`, `minScore=0.6`), compiles a strict contextual prompt, and invokes `chatLanguageModel.generate(prompt)`.

### 2.3 [RagController](file:///home/sagen/Projects/documind/src/main/java/com/ai/documind/controller/RagController.java)
- **Role**: REST API gateway.
- **Responsibilities**:
  - Validates request payloads (preventing empty prompts or blank text ingestions).
  - Routes ingestion requests to `RagService.ingestText()`.
  - Routes query requests to `RagService.askQuestion()`.

---

## 3. Data Flow & Request Lifecycles

### 3.1 Document Ingestion Sequence
```
Client             RagController           RagService            Gemini Embedding        PgVector Store
  |                     |                      |                        |                      |
  |--- POST /ingest --->|                      |                        |                      |
  |    { "text": ... }  |--- ingestText() ---->|                        |                      |
  |                     |                      |-- DocumentSplitter --->|                      |
  |                     |                      |   recursive(300, 50)   |                      |
  |                     |                      |                        |                      |
  |                     |                      |--- embed(segment) ---->|                      |
  |                     |                      |<-- 768-d vector -------|                      |
  |                     |                      |                                               |
  |                     |                      |--- add(embedding, segment) ------------------>|
  |                     |                      |<-- INSERT completed --------------------------|
  |                     |<-- Ingest status ----|
  |<-- 200 OK ----------|
```

### 3.2 Retrieval & Generation Sequence
```
Client             RagController           RagService            Gemini Embedding        PgVector Store       Gemini Chat
  |                     |                      |                        |                      |                   |
  |--- POST /ask ------>|                      |                        |                      |                   |
  |  { "question": .. } |--- askQuestion() --->|                        |                      |                   |
  |                     |                      |--- embed(question) --->|                      |                   |
  |                     |                      |<-- query vector -------|                      |                   |
  |                     |                      |                                               |                   |
  |                     |                      |--- search(k=3, minScore=0.6) ---------------->|                   |
  |                     |                      |<-- top matching TextSegments -----------------|                   |
  |                     |                      |                                                                   |
  |                     |                      |-- assemble strict prompt template ------------------------------->|
  |                     |                      |   [Context: matches] [Question: query]                            |
  |                     |                      |<-- Grounded answer -----------------------------------------------|
  |                     |<-- Answer JSON ------|
  |<-- 200 OK ----------|
```

### Visual Sequence Diagram
An interactive standalone HTML sequence diagram has been generated using Archify:
- **Interactive File**: [`documind-sequence.html`](file:///home/sagen/Projects/documind/docs/documind-sequence.html)

---

## 4. API Reference

### 4.1 Ingest Document
- **Endpoint**: `POST /api/rag/ingest`
- **Headers**: `Content-Type: application/json`
- **Request Body**:
```json
{
  "text": "DocuMind is an enterprise knowledge assistant developed using Spring Boot, PostgreSQL with pgvector, and Google Gemini."
}
```
- **Response**: `200 OK`
```
Successfully ingested 1 text chunks into pgvector!
```

**Curl Example**:
```bash
curl -X POST http://localhost:8080/api/rag/ingest \
  -H "Content-Type: application/json" \
  -d '{"text": "DocuMind provides enterprise RAG capabilities using LangChain4j."}'
```

---

### 4.2 Ask Question
- **Endpoint**: `POST /api/rag/ask`
- **Headers**: `Content-Type: application/json`
- **Request Body**:
```json
{
  "question": "What technologies does DocuMind use?"
}
```
- **Response**: `200 OK`
```json
{
  "answer": "Based on the provided context, DocuMind uses the following technologies:\n* Spring Boot\n* PostgreSQL with pgvector\n* Google Gemini"
}
```

**Curl Example**:
```bash
curl -X POST http://localhost:8080/api/rag/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "What technologies does DocuMind use?"}'
```

---

## 5. Verification & Testing

To execute automated tests against the running database and Google Gemini API:
```bash
./mvnw test -Dtest=RagServiceIntegrationTest
```
Result:
```text
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
