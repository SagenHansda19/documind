# DocuMind Enterprise Architecture & Technical Reference

DocuMind is an enterprise-grade Retrieval-Augmented Generation (RAG) knowledge assistant built using **Spring Boot 3.3.0**, **PostgreSQL with pgvector**, and a **Resilient Dual-Provider LLM Engine** (**Groq LPU Cloud** for sub-second inference + **Google Gemini** for multimodal reasoning and vector embeddings), orchestrated via **LangChain4j 0.35.0**.

---

## 1. System Architecture Overview

DocuMind decouples document parsing, semantic chunking, and vector storage from conversational memory and inference. This architecture ensures high-throughput enterprise knowledge retrieval with strict isolation between client queries, factual context, and model generation.

```
+---------------------------------------------------------------------------------------------------+
|                                  Spring Boot 3.3.0 Application                                    |
|                                                                                                   |
|  [Web UI / REST Client] ---> [ RagController ] ---> [ RagService ] <------- [ AiConfig ]         |
|         (:8080)                  (:8080)        |    (Core Engine)    (Provider Routing & Keys)   |
|                                                 |           |                                     |
|                                                 |   [ChatMemory Store]                            |
|                                                 |  (Per-Session History)                          |
+-------------------------------------------------+---+-------+-------------------------------------+
                                                 / \          |
                                                /   \         |
                       (Store / Search Vector) /     \        +------------------+
                                              v       \                          |
                     +----------------------------+    \ (Embeddings)            | (Inference)
                     |         PostgreSQL         |     v                        v
                     |          (pgvector)        |   +-------------------+    +--------------------+
                     |                            |   | Google AI Studio  |    |     Groq Cloud     |
                     | • Database: documind_db    |   |     (Gemini)      |    |     (LPU Engine)   |
                     | • Table: doc_embeddings    |   |                   |    |                    |
                     | • Dimension: 768           |   | • Embed: 768-d    |    | • Chat: LPU Model  |
                     | • Cosine distance index    |   |   gemini-embedding|    |   openai/gpt-oss   |
                     |                            |   | • Chat (Failover):|    |   or llama-3.3-70b |
                     |                            |   |   gemini-3.8-flash|    | • Sub-500ms speed  |
                     +----------------------------+   +-------------------+    +--------------------+
```

---

## 2. Core Theory: Semantic Embeddings vs. Keyword Search

To understand how DocuMind retrieves knowledge, it is essential to understand how text binds to vectors, why traditional keyword databases fail at contextual question answering, and why Spring Boot beats Python scripts for production RAG.

### 2.1 What Are Embeddings? (Meaning as Geometry)
At a conceptual level, **embeddings convert text semantics into multi-dimensional coordinates**:
- Computers do not intrinsically understand grammar, intent, or the conceptual meaning of words.
- An embedding model (such as `gemini-embedding-001`) reads a chunk of text and translates its semantic meaning into a list of 768 floating-point numbers—a coordinate vector in 768-dimensional space.
- In this mathematical coordinate space, **distance equals conceptual difference**: sentences and phrases that mean similar things are plotted very close together, while unrelated topics are placed far apart.
- **Example**: 
  - Text A: *"How many days off do full-time employees receive?"*
  - Text B: *"Staff are allocated 20 days of annual paid leave."*
  - Even though Text A and Text B share almost **zero overlapping vocabulary** (no common nouns/verbs), their coordinate vectors land right next to each other because their underlying *meaning* is identical.

### 2.2 Chunk-to-Embedding Binding & Vector Storage
How does a physical document actually link to vector geometry in PostgreSQL?
- **The Text Chunk**: The physical block of raw text parsed from your PDF (e.g., your resume's education or project description), split with overlapping boundaries (`DocumentSplitters.recursive(800, 150)`).
- **The Embedding Vector**: The 768-dimensional float array generated immediately for that specific chunk, acting as its semantic coordinate in PostgreSQL (`pgvector`).
- **The Database Link**: Each row in `doc_embeddings` pairs one exact text chunk with its vector coordinate and metadata (`file_name`). During a query, pgvector searches the coordinate space and retrieves the exact corresponding text chunk instantly.

```
[ Uploaded PDF / Document ]
           │
           ▼
[ Raw Text Chunk ]  ──(gemini-embedding-001)──►  [ 768-d Float Vector ]
(e.g., Education & Skills)                       [ -0.041, 0.082, 0.019 ... ]
           │                                                  │
           └────────────────────────┬─────────────────────────┘
                                    ▼
                 INSERT INTO doc_embeddings (
                   id, 
                   text,                <-- Bound text chunk
                   embedding,           <-- 768-d vector coordinate
                   metadata             <-- {"file_name": "resume.pdf"}
                 );
```

### 2.3 Why pgvector Semantic Search Beats Keyword Lookups
Traditional databases rely on keyword searching (like SQL `LIKE '%keyword%'`, `ILIKE`, or inverted full-text search indexes like Lucene / Postgres tsvector):

| Capability | Traditional Keyword Search | pgvector Semantic Search (DocuMind) |
| :--- | :--- | :--- |
| **Lookup Mechanism** | Lexical string matching (exact tokens/stems) | Vector distance in high-dimensional coordinate space (`<=>` cosine distance) |
| **Synonyms & Rephrasings** | **Fails**: Missing the exact keyword yields 0 results (e.g. searching "salary" misses "compensation") | **Excels**: Identifies synonyms naturally because concepts share coordinate space |
| **Vocabulary Mismatch** | **High Fragility**: Query and document must use identical terminology | **Zero Fragility**: Bridges user intent and corporate jargon automatically |
| **Polysemy & Context** | Confuses word meanings (e.g., "bank" financial vs "bank" of a river) | Disambiguates meaning using surrounding sentence context in the vector |
| **Ranking by Relevance** | Based on keyword frequency (TF-IDF / BM25) regardless of deeper intent | Ranked by cosine similarity (`1 - cosine_distance`), filtering noise with `minScore` thresholds |

When a user asks DocuMind a question, the system converts the question into a 768-dimensional vector, runs a cosine distance query against PostgreSQL via `pgvector`, and retrieves the most conceptually relevant chunks—even if the user phrased the question completely differently from how the document was written.

### 2.4 Java Enterprise vs. Python / LangChain / LangGraph
While Python is common for AI research notebooks and proof-of-concept scripts, DocuMind uses **Spring Boot 3.3.0 and LangChain4j** for hardened enterprise production:

- **LangChain4j vs. Python LangChain**: While Python popularised AI prototyping, LangChain4j brings these exact RAG abstractions (document parsers, splitters, embedding models, vector stores, and conversational memory) directly to Java. This provides compile-time type safety, robust memory handling, and high-throughput JVM concurrency without Python's Global Interpreter Lock (GIL) bottlenecks.
- **Why Not LangGraph?**: LangGraph is designed for complex, cyclical multi-agent loops (where autonomous agents debate, iterate in loops, or branch conditionally). For an enterprise document RAG assistant, a clean, linear pipeline (`RagService`) combined with per-session memory (`MessageWindowChatMemory`) is deterministic, predictable, resilient, and fully sufficient—without introducing the latency and unpredictable token spend of cyclic agent graphs.
- **PostgreSQL & Connection Pooling**: Production-grade connection pooling via **HikariCP** and transactional safety ensure that vector insertions, similarity scans, and metadata lookups operate seamlessly within a single unified enterprise database pool.
- **Single-Artifact Deployment**: Packaged into a self-contained, containerized executable JAR without fragile Python virtual environments, pip wheel compilation failures, or runtime C-dependency drift.

---

## 3. Component Breakdown

### 3.1 [AiConfig](file:///home/sagen/Projects/documind/src/main/java/com/ai/documind/config/AiConfig.java)
- **Role**: Configuration bean factory and LLM Provider Router.
- **Responsibilities**:
  - **Self-Healing Key Resolution**: Triple fallback checking `application.properties`, OS environment variables, and local `.env` with auto-formatting (`AQ.` prefix enforcement).
  - **Multi-Provider Routing (`ai.provider=auto|groq|gemini`)**:
    - **Groq LPU (`OpenAiChatModel`)**: Targets Groq's high-speed endpoint (`https://api.groq.com/openai/v1`) delivering sub-second completions with zero 503 throttling.
    - **Google Gemini (`GoogleAiGeminiChatModel`)**: Configured with 60s timeout resilience and exponential backoff retry.
    - **Resilient Failover**: In `auto` mode, automatically routes prompts through Groq and transparently fails over to Gemini if rate limits or network issues occur.
  - **Embedding Engine (`GoogleAiEmbeddingModel`)**: Strictly configured with `outputDimensionality(768)` for vector parity with pgvector.

### 3.2 [RagService](file:///home/sagen/Projects/documind/src/main/java/com/ai/documind/service/RagService.java)
- **Role**: Core RAG orchestration service and document parser.
- **Responsibilities**:
  - **Document Parsing**: Utilizes `ApachePdfBoxDocumentParser` for `.pdf` documents and `TextDocumentParser` for `.txt`, `.md`, `.json`, and `.csv`.
  - **Overlapping Segmentation**: Employs `DocumentSplitters.recursive(300, 50)` to ensure semantic continuity across chunk boundaries.
  - **Conversational Memory**: Manages isolated `MessageWindowChatMemory` instances keyed by `sessionId`, ensuring that multi-turn dialogue context is preserved without bloating memory with raw vector context.
  - **Contextual Prompt Assembly**: Performs cosine similarity search (`topK=4`, `minScore=0.5`), injects matched document snippets into system instructions, and returns verified citations with relevance scores.

### 3.3 [RagController](file:///home/sagen/Projects/documind/src/main/java/com/ai/documind/controller/RagController.java)
- **Role**: REST API gateway.
- **Endpoints**:
  - `POST /api/rag/upload`: Accepts multipart document files (`.pdf`, `.txt`, `.md`).
  - `POST /api/rag/ingest`: Backward-compatible raw text ingestion.
  - `POST /api/rag/ask`: Accepts `{ "question": "...", "sessionId": "..." }` and returns answers with source citations.
  - `GET /api/rag/chat/{sessionId}/history`: Returns dialogue history.
  - `DELETE /api/rag/chat/{sessionId}`: Resets conversational memory for a session.

---

## 4. Data Flow & Request Lifecycles

### 4.1 Multipart Document Ingestion Lifecycle
```
Client                 RagController              RagService             Doc Parser            Embedding Model         PgVector
  |                          |                        |                       |                       |                   |
  |-- POST /api/rag/upload ->|                        |                       |                       |                   |
  |   (Multipart File)       |-- uploadFile(file) --->|                       |                       |                   |
  |                          |                        |-- select parser ----->|                       |                   |
  |                          |                        |<-- parsed Document ---|                       |                   |
  |                          |                        |-- split(300, 50) ---->|                       |                   |
  |                          |                        |-- embed(segment) ---------------------------->|                   |
  |                          |                        |<-- 768-d vector ------------------------------|                   |
  |                          |                        |-- add(embedding, segment) --------------------------------------->|
  |                          |<-- UploadResponse -----|                                                                   |
  |<-- 200 OK (chunks JSON) -|
```

### 4.2 Conversational Query & Failover Inference
```
Client                 RagController              RagService              ChatMemory             PgVector         Primary LLM (Groq)   Fallback (Gemini)
  |                          |                        |                       |                      |                     |                    |
  |-- POST /api/rag/ask ---->|                        |                       |                      |                     |                    |
  |   (query + sessionId)    |-- askQuestion() ------>|                       |                      |                     |                    |
  |                          |                        |-- embed & search --------------------------->|                     |                    |
  |                          |                        |<-- top-4 matches ----------------------------|                     |                    |
  |                          |                        |-- fetch history ----->|                                            |                    |
  |                          |                        |<-- history turns -----|                                            |                    |
  |                          |                        |-- generate(system prompt + history + question) ------------------->|                    |
  |                          |                        |   (If primary succeeds: return completion)                         |                    |
  |                          |                        |   (If primary fails with 429/503: auto-failover) -------------------------------------->|
  |                          |                        |<-- completion answer -------------------------------------------------------------------|
  |                          |                        |-- add turn to memory->|                                            |                    |
  |                          |<-- AskResponse --------|                                                                                         |
  |<-- 200 OK (answer + JSON)|
```

---

## 5. API Reference

### 5.1 Multipart File Upload
- **Endpoint**: `POST /api/rag/upload`
- **Content-Type**: `multipart/form-data`
- **Parameters**: `file` (Binary file: `.pdf`, `.txt`, `.md`)
- **Example Response**:
```json
{
  "fileName": "security-guideline.pdf",
  "fileSize": 14200,
  "chunksCount": 8,
  "message": "Successfully parsed and stored 8 segments into pgvector.",
  "success": true
}
```

### 5.2 Multi-Turn Conversational Query
- **Endpoint**: `POST /api/rag/ask`
- **Content-Type**: `application/json`
- **Request Body**:
```json
{
  "question": "What is the token rotation interval?",
  "sessionId": "session-prod-01"
}
```
- **Example Response**:
```json
{
  "answer": "According to the security guidelines, all API tokens rotate every 90 days.",
  "sessionId": "session-prod-01",
  "sources": [
    {
      "fileName": "security-guideline.pdf",
      "score": 0.884,
      "text": "All API requests require TLS 1.3 encryption and API tokens rotate every 90 days."
    }
  ]
}
```

---

## 6. Security & Configuration Conventions

DocuMind strictly enforces a **Zero-Secret VCS Policy**:
- `.env` is permanently excluded via `.gitignore`.
- Configuration templates are provided in `.env.example`:
  ```properties
  DB_USERNAME=admin
  DB_PASSWORD=admin
  DATABASE_URL=jdbc:postgresql://localhost:5432/documind_db
  AI_PROVIDER=auto
  GEMINI_API_KEY=your_gemini_api_key_here
  GROQ_API_KEY=gsk_your_groq_api_key_here
  ```
- `application.properties` references safe environment placeholders with empty defaults: `${GEMINI_API_KEY:}` and `${GROQ_API_KEY:}`.
