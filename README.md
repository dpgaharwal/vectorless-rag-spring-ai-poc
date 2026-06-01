# Vectorless RAG POC — Spring AI Native Implementation

> A research-grade Proof of Concept demonstrating **tree-based, vectorless Retrieval-Augmented Generation** natively implemented in Spring AI, with a fully working side-by-side benchmark against traditional vector RAG — including a real-time comparison UI.


<img width="996" height="1301" alt="img" src="https://github.com/user-attachments/assets/9241b15e-636c-4a85-83f9-6d88ce452943" />


---

## ⚠️ LLM Provider Note

This POC has been architected to support **four LLM providers** — OpenAI, Ollama, Gemini, and Claude Anthropic — with zero code changes required to switch between them. Configuration is the only thing that needs to change.

**End-to-end testing was performed with OpenAI (gpt-4o-mini) and Ollama (llama3.2 on Apple Silicon).**

| Provider | Status | Notes |
|---|---|---|
| **OpenAI** | ✅ Tested & Working | Primary provider used for validation |
| **Ollama (llama3.2)** | ✅ Tested & Working | Recommended on Apple Silicon — fast via Metal GPU |
| Gemini (gemini-2.0-flash) | ⚠️ Provisioned | Free tier quota exhausted during development |
| Claude Anthropic | ⚠️ Provisioned | API key not configured during development |

> **Known issue:** Spring AI versions 1.1.1–1.1.5 have a bug where an empty `extra_body` field is serialized into OpenAI API requests, causing a `400 Unrecognized request argument` error. This project uses Spring AI **1.1.6** where the fix is included.

---

## What This Project Is About

This project started with a simple question — **is there a better way to do RAG?**

Traditional RAG is the industry default. It works. But when you look closely at how it retrieves information, there are real problems that nobody talks about enough: it chunks documents arbitrarily, it finds text that is semantically similar rather than answer-containing, and it gives you zero visibility into why a particular chunk was selected.

This POC implements **Vectorless RAG** — an alternative approach inspired by [PageIndex by VectifyAI](https://github.com/VectifyAI/PageIndex) — where retrieval is driven by **LLM reasoning over a document's hierarchical structure** instead of vector similarity. The entire algorithm (tree building, tree storage, recursive tree navigation) is implemented natively in Java using Spring AI — something that did not previously exist in the Java ecosystem.

A fully working **Vector RAG pipeline** runs in parallel, enabling direct benchmarking of both approaches on the same query, same document, same LLM.

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                         REST API Layer                           │
│               BenchmarkController (/api/rag/*)                   │
└────────────────────┬─────────────────────────┬───────────────────┘
                     │                         │
        ┌────────────▼──────────┐   ┌──────────▼──────────────┐
        │  Vectorless Pipeline  │   │   Vector RAG Pipeline   │
        │                       │   │                         │
        │  DocumentIndexService │   │  VectorRagPipeline      │
        │  TreeBuilder          │   │  TokenTextSplitter      │
        │  TreeStore            │   │  pgvector + embeddings  │
        │  TreeDocumentRetriever│   │  similarity search      │
        └────────────┬──────────┘   └──────────┬──────────────┘
                     │                         │
        ┌────────────▼─────────────────────────▼──────────────┐
        │                     LLM Layer                        │
        │      OpenAI / Ollama / Gemini / Claude (switchable)  │
        └──────────────────────────────────────────────────────┘
                     │
        ┌────────────▼──────────────────────────────────────┐
        │                  Storage Layer                     │
        │   Tree JSON — disk file + in-memory ConcurrentMap  │
        │   pgvector (PostgreSQL) — vector embeddings store  │
        └────────────────────────────────────────────────────┘
```

---

## Part 1 — Traditional RAG

### How It Works

Traditional RAG is a two-phase pipeline: an offline indexing phase where the document is processed and stored, and an online retrieval phase where queries are answered.

**Indexing Phase (done once):**

```
PDF Document
     ↓
Split into fixed-size chunks
(500 tokens, 50-token overlap via TokenTextSplitter)
     ↓
Each chunk → Embedding Model → float[] vector
     ↓
Store (chunk text + vector) in pgvector
```

**Query Phase (done per query):**

```
User Query
     ↓
Query → Embedding Model → float[] vector
     ↓
Cosine similarity search across all stored vectors
     ↓
Top-K most similar chunks retrieved (K=5)
     ↓
Chunks stuffed into prompt → LLM → Answer
```

### What Works Well

For simple, factual questions over short documents, traditional RAG performs well. The pipeline is well-understood, has mature tooling, and integrates directly with Spring AI's `VectorStore` abstraction.

### Where It Breaks Down

**Problem 1 — Chunking is arbitrary.** Splitting every 500 tokens has no awareness of document structure. A paragraph gets split in the middle. A table header ends up in one chunk and the table data in another. The document's natural boundaries — sections, subsections, headings — are completely ignored.

**Problem 2 — Similarity is not the same as relevance.** Cosine similarity finds chunks that are semantically close to the query. But semantically close does not mean answer-containing. If you ask "what was the Q3 revenue?", a chunk discussing "annual revenue trends" might score higher than the chunk that actually has the Q3 number.

**Problem 3 — No explainability.** When the RAG pipeline returns a wrong answer, you cannot trace why. You don't know which chunks were retrieved, why those chunks were chosen, or which part of the document the answer came from. Debugging is guesswork.

**Problem 4 — Embedding cost at scale.** Every document chunk requires an embedding model call at index time. Every query requires one at retrieval time. For large document sets with frequent queries, this adds up in both latency and cost.

---

## Part 2 — Vectorless RAG

### The Core Idea

Vectorless RAG replaces the "find similar chunks" step with "reason about document structure and navigate to the right section." Instead of asking "which text is most similar to this query?", it asks "given this document's structure, which section most likely contains the answer?"

The inspiration is PageIndex — an open-source Python library from VectifyAI. This POC is the first native Java/Spring AI implementation of the same algorithm.

### How It Works

**Indexing Phase (done once):**

```
PDF Document
     ↓
Spring AI PagePdfDocumentReader → List<String>
(one page = one unit of content)
     ↓
LLM analyzes page summaries → builds Hierarchical Tree JSON

Document Root (pages 0-100)
├── Introduction (pages 0-5)
│     └── Background (pages 1-3)
├── Methodology (pages 6-30)
│     ├── Data Collection (pages 6-15)
│     └── Analysis Framework (pages 16-30)
├── Results (pages 31-70)
│     ├── Q1 Performance (pages 31-45)
│     ├── Q2 Performance (pages 46-58)
│     └── Q3 Performance (pages 59-70)
└── Conclusion (pages 71-100)

     ↓
Tree persisted to disk as JSON (tree-store/{docId}.json)
+ loaded into in-memory ConcurrentHashMap cache
(built once, reused for ALL future queries — idempotent)
```

**Query Phase (done per query):**

```
User Query: "What was the Q3 revenue?"
     ↓
TreeDocumentRetriever.retrieve(query)
     ↓
LLM receives tree node titles + one-line summaries only
(NOT full page content — keeps navigation prompt small)
     ↓
LLM reasons: "Q3 revenue → navigate to Results"
→ node_id picked, navigate into "Results" children
     ↓
LLM reasons: "Q3 Performance (pages 59-70) is the right section"
→ leaf node reached
     ↓
Pages 59-70 fetched directly by page range [startIndex, endIndex)
     ↓
Pages + query → LLM → Answer + nodePath trace
```

### The Navigation Algorithm

The retrieval is a **recursive tree traversal** — conceptually similar to how AlphaGo uses a policy network to pick moves rather than brute-forcing every position.

```
navigateTree(rootNode, query, depth=0)
  ├── if leaf node OR depth >= MAX_DEPTH(3) → return node
  ├── LLM call: given these child nodes + query, pick one node_id
  ├── find child by node_id
  └── navigateTree(chosenChild, query, depth+1)  [recursive]
```

Each recursion level = one LLM call. With MAX_DEPTH=3, any query makes at most **3 LLM navigation calls** regardless of document size. The LLM only sees node titles and summaries — not full page content — so each navigation call is fast and cheap.

### What This Solves

**Chunking** — Documents are indexed by their natural structure, not arbitrary token counts. A section stays together. A table stays together. Cross-references within a section are preserved.

**Relevance** — The LLM navigates to the section most likely to *contain* the answer, not the section most *similar* to the query. For structured documents this is a meaningful difference.

**Explainability** — Every query produces a `nodePath` — the exact sequence of sections traversed. "Answer came from Results → Q3 Performance → pages 59-70" is impossible with vector RAG.

**Cost** — No embedding model required. Tree built once. Navigation uses short-prompt LLM calls (titles + summaries only).

---

## Head-to-Head Comparison

| | Traditional RAG | Vectorless RAG |
|---|---|---|
| Indexing strategy | Split by token count (arbitrary) | Split by document structure (natural) |
| Retrieval mechanism | Cosine similarity search | LLM reasoning + recursive tree navigation |
| Vector database | ✅ Required (pgvector) | ❌ Not required |
| Embedding model | ✅ Required at index + query time | ❌ Not required |
| Explainability | ❌ Black box — no visibility | ✅ Full node path returned per query |
| Cross-section context | ❌ Chunks are isolated fragments | ✅ Sections are structurally coherent |
| Best suited for | General-purpose, short/flat documents | Structured docs with clear hierarchy |
| Cold start cost | Higher — embed all chunks upfront | Lower — one LLM call to build tree |
| Per-query LLM calls | 1 (answer generation) | 1–3 (navigation) + 1 (answer generation) |

---

## Real Benchmark Results

Tested on a 4-page PDF (Accelio PDF bookmark sample), query: *"What is this document about?"*

```json
{
  "query": "What is this document about?",
  "vectorless_rag": {
    "answer": "This document demonstrates the functionality of primary and secondary bookmarks in a PDF file, created using Accelio Present Central 5.4 and Output Designer 5.4.",
    "pagesRetrieved": 2,
    "nodePath": ["Main content", "Sample files"],
    "totalTimeMs": 5711,
    "vectorDbUsed": false,
    "explainable": true
  },
  "vector_rag": {
    "answer": "This document is a technical sample related to creating and managing bookmarks in PDF files using Accelio Present Output Designer software.",
    "chunksRetrieved": 5,
    "nodePath": [],
    "totalTimeMs": 5807,
    "vectorDbUsed": true,
    "explainable": false
  }
}
```

Key observation — `nodePath: ["Main content", "Sample files"]` shows exactly which tree nodes the vectorless pipeline traversed to reach the answer. Vector RAG has no equivalent trace.

---

## Multi-LLM Support

All four providers are configured simultaneously. Switching requires **one line change** in `AiConfig.java`:

| Provider | Qualifier | Model | Tested |
|---|---|---|---|
| OpenAI | `openAiChatModel` | gpt-4o-mini | ✅ |
| Ollama | `ollamaChatModel` | llama3.2 | ✅ (Apple Silicon) |
| Gemini | `googleGenAiChatModel` | gemini-2.0-flash | ⚠️ Provisioned |
| Claude | `anthropicChatModel` | claude-haiku-4-5 | ⚠️ Provisioned |

### Switching Providers

**Step 1 — Change qualifier in `AiConfig.java`:**
```java
// OpenAI
@Qualifier("openAiChatModel")

// Ollama (recommended for local dev on Mac)
@Qualifier("ollamaChatModel")

// Gemini
@Qualifier("googleGenAiChatModel")

// Claude
@Qualifier("anthropicChatModel")
```

**Step 2 — Set API key in `application.yml`:**
```yaml
spring.ai.openai.api-key: your-key         # OpenAI
spring.ai.google.genai.api-key: your-key   # Gemini
spring.ai.anthropic.api-key: your-key      # Claude
spring.ai.ollama.base-url: http://localhost:11434  # Ollama — no key needed
```

**For Ollama on Mac (Apple Silicon):**
```bash
brew install ollama
ollama serve
ollama pull llama3.2
ollama pull nomic-embed-text   # for vector RAG embeddings
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 3.4.5 |
| AI Framework | Spring AI 1.1.6 |
| PDF Parsing | Spring AI PagePdfDocumentReader (Apache PDFBox) |
| Vector Store | pgvector (PostgreSQL 16 extension) |
| Local LLM | Ollama (native on Mac, Docker on Windows/Linux) |
| Tree Persistence | Jackson JSON serialization + disk + ConcurrentHashMap |
| Build | Maven 3.8+ |
| Java | 21 |

---

## Project Structure

```
vectorless-rag-poc/
├── src/main/java/com/vectorlessrag/
│   ├── config/
│   │   ├── AiConfig.java           # LLM provider wiring + qualifier
│   │   └── CorsConfig.java         # CORS for demo UI
│   ├── controller/
│   │   └── BenchmarkController.java  # REST endpoints
│   ├── pipeline/
│   │   ├── DocumentIndexService.java  # Orchestrates both pipelines
│   │   └── VectorRagPipeline.java     # Traditional RAG (chunk → embed → store → retrieve)
│   ├── retriever/
│   │   └── TreeDocumentRetriever.java # Core vectorless retrieval algorithm
│   └── tree/
│       ├── TreeBuilder.java           # LLM-driven tree construction from pages
│       ├── TreeNode.java              # Recursive tree data model
│       └── TreeStore.java             # Two-tier persistence (disk + memory)
├── rag-demo.html                      # Standalone comparison UI (drag & drop)
├── docker-compose.yml                 # pgvector + Ollama
├── pom.xml
└── src/main/resources/
    └── application.yml
```

---

## Prerequisites

```
Java 21+
Maven 3.8+
Docker Desktop
OpenAI API key OR Ollama installed (Mac recommended for Ollama)
```

---

## How to Run

### Mac (with Ollama — recommended)

```bash
# 1. Install and start Ollama
brew install ollama
ollama serve

# 2. Pull models (new terminal tab)
ollama pull llama3.2
ollama pull nomic-embed-text

# 3. Start pgvector
docker run -d --name pgvector \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=vectorrag \
  -p 5432:5432 \
  pgvector/pgvector:pg16

docker exec -it pgvector psql -U postgres -d vectorrag \
  -c "CREATE EXTENSION IF NOT EXISTS vector;"

# 4. Set qualifier to Ollama in AiConfig.java, then run
mvn spring-boot:run
```

### Windows / Linux (with Docker)

```bash
# 1. Start all infrastructure
docker-compose up -d

# Pull Ollama model inside container
docker exec -it ollama ollama pull llama3.2
docker exec -it ollama ollama pull nomic-embed-text

# Create pgvector extension
docker exec -it pgvector psql -U postgres -d vectorrag \
  -c "CREATE EXTENSION IF NOT EXISTS vector;"

# 2. Run application
mvn spring-boot:run
```

> **Windows note:** pgvector runs on port 5433 in docker-compose (to avoid conflict with any local PostgreSQL on 5432). Update `application.yml` datasource URL accordingly.

---

## API Reference

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/rag/index` | Index a PDF — extracts pages, builds tree, chunks for vector store |
| `GET` | `/api/rag/vectorless/query` | Query via tree navigation only |
| `GET` | `/api/rag/benchmark` | Run query through BOTH pipelines, return side-by-side results |

### Parameters

**`/api/rag/index`**
- `pdfPath` — absolute path to PDF on the server machine

**`/api/rag/vectorless/query`** and **`/api/rag/benchmark`**
- `query` — question to answer
- `documentId` — document ID returned from the index call

### Example Calls

```bash
# Index
curl -X POST "http://localhost:8080/api/rag/index?pdfPath=/Users/you/docs/report.pdf"

# Vectorless query only
curl "http://localhost:8080/api/rag/vectorless/query?query=what+is+the+Q3+revenue&documentId=report"

# Full benchmark — both pipelines
curl "http://localhost:8080/api/rag/benchmark?query=what+is+the+Q3+revenue&documentId=report"
```

---

## Comparison Demo UI

The project includes `rag-demo.html` — a standalone dark-themed UI that runs both pipelines simultaneously and animates the retrieval process.

**How to use:**
1. Make sure Spring Boot is running on port 8080
2. Open `rag-demo.html` directly in your browser
3. Drag and drop (or click to select) your PDF
4. Edit the path field to the **absolute path** of your PDF on disk
5. Type a query and click **Run**

**What you'll see:**
- Left panel — Tree navigation animating node by node, LLM picks shown in real time
- Right panel — Chunk scanning with similarity scores appearing one by one
- Benchmark results — side-by-side answers, latency bars, node path trace, explainability badge

> CORS must be enabled on the Spring Boot server. `CorsConfig.java` handles this — it's included in the project.

---

## Why This Matters

**Why build this in Java instead of using Python PageIndex?**

The Python PageIndex SDK handles everything in 3 lines — tree building, storage, and retrieval are hidden inside the library. Building it natively in Spring AI required implementing the full algorithm from scratch: tree construction, recursive LLM navigation, page-range fetching, two-tier persistence. This demonstrates deep understanding of the algorithm, not just library usage. Production Java backends cannot call a Python sidecar for every query — native Spring AI integration means zero cross-language overhead and direct enterprise integration.

**What is the actual difference in retrieval quality?**

Vector RAG finds text that is semantically *similar* to the query. Vectorless RAG finds text that is structurally *relevant* — the LLM navigates to the section most likely to *contain* the answer. For structured documents like financial reports, technical specifications, and legal documents with clear section hierarchy, this is a meaningful and measurable difference.

**How does this scale?**

The tree is built once at index time and cached on disk. Re-indexing the same document is a no-op — the cached tree is reloaded. Per-query cost is 1–3 lightweight LLM navigation calls on short prompts (node titles and summaries only, not full page content), plus one answer generation call. The vector DB and embedding model are completely absent from the retrieval path.

---

## Inspired By

[PageIndex by VectifyAI](https://github.com/VectifyAI/PageIndex) — the original Python implementation of vectorless RAG that inspired this Java port.

This project is the first known native Java/Spring AI implementation of the PageIndex algorithm.
