# Vectorless RAG POC — Spring AI Native Implementation

> A research-grade Proof of Concept demonstrating **tree-based, vectorless Retrieval-Augmented Generation** natively implemented in Spring AI, with a side-by-side benchmark against traditional vector RAG.

---

## What This Project Is About

This project started with a simple question — **is there a better way to do RAG?**

Traditional RAG is the industry default. It works. But when you look closely at how it retrieves information, there are real problems that nobody talks about enough: it chunks documents arbitrarily, it finds text that is semantically similar rather than answer-containing, and it gives you zero visibility into why a particular chunk was selected.

This POC implements **Vectorless RAG** — an alternative approach inspired by [PageIndex by VectifyAI](https://github.com/VectifyAI/PageIndex) — where retrieval is driven by **LLM reasoning over a document's hierarchical structure** instead of vector similarity. The entire algorithm is implemented natively in Java using Spring AI, with a benchmark endpoint that runs both pipelines on the same query so you can compare results directly.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        REST API Layer                           │
│              BenchmarkController (/api/rag/*)                   │
└───────────────────┬────────────────────────┬────────────────────┘
                    │                        │
       ┌────────────▼──────────┐  ┌──────────▼──────────────┐
       │  Vectorless Pipeline  │  │   Vector RAG Pipeline   │
       │                       │  │                         │
       │  PDF → Tree Building  │  │  PDF → Chunking         │
       │  Tree Storage         │  │  Embedding              │
       │  LLM Tree Navigation  │  │  pgvector similarity    │
       └────────────┬──────────┘  └──────────┬──────────────┘
                    │                        │
       ┌────────────▼────────────────────────▼──────────────┐
       │                    LLM Layer                        │
       │         Ollama / Gemini / Claude (switchable)       │
       └─────────────────────────────────────────────────────┘
                    │
       ┌────────────▼──────────────────────────────────────┐
       │               Storage Layer                        │
       │   Tree JSON (disk + in-memory cache)               │
       │   pgvector (PostgreSQL) for Vector RAG             │
       └───────────────────────────────────────────────────┘
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
(e.g. every 500 tokens, with 50-token overlap)
     ↓
Each chunk → Embedding Model → float[] vector
     ↓
Store (chunk text + vector) in vector database (pgvector)
```

**Query Phase (done per query):**

```
User Query
     ↓
Query → Embedding Model → float[] vector
     ↓
Cosine similarity search across all stored vectors
     ↓
Top-K most similar chunks retrieved
     ↓
Chunks stuffed into prompt → LLM → Answer
```

### What Works Well

For simple, factual questions over short documents, traditional RAG performs well. The pipeline is well-understood, has mature tooling, and works out of the box with Spring AI's `RetrievalAugmentationAdvisor`.

### Where It Breaks Down

The deeper you look, the more cracks appear:

**Problem 1 — Chunking is arbitrary.** Splitting every 500 tokens has no awareness of document structure. A paragraph about revenue gets split in the middle. A table header ends up in one chunk and the table data in another. The document's natural boundaries — sections, subsections, headings — are completely ignored.

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
Extract pages — one page = one unit of content
     ↓
LLM analyzes page summaries → builds Hierarchical Tree

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
Tree persisted to disk as JSON + loaded into memory
(built once, reused for all future queries)
```

**Query Phase (done per query):**

```
User Query: "What was the Q3 revenue?"
     ↓
LLM receives tree structure (titles + one-line summaries only)
     ↓
LLM reasons: "Q3 revenue → navigate to Results → Q3 Performance"
     ↓
LLM receives children of "Results" node
     ↓
LLM reasons: "Q3 Performance (pages 59-70) is the right section"
     ↓
Pages 59-70 fetched directly by page range
     ↓
Pages stuffed into prompt → LLM → Answer
```

### Why This Is Different

The retrieval is a **guided navigation** — like using a table of contents — not a similarity search. The LLM is not finding text that sounds like the query. It is reasoning about document structure to find the section that *contains* the answer.

This is similar in spirit to how AlphaGo uses a policy network to decide which moves to explore, rather than brute-forcing every possible position. Instead of scanning all chunks with cosine similarity, the model uses reasoning to navigate directly to the relevant section.

Each level of the tree is one LLM call. With a maximum depth of 3, any query requires at most 3 LLM navigation calls — regardless of document size.

### What This Solves

**Chunking** — Documents are indexed by their natural structure, not by arbitrary token counts. A section stays together. A table stays together. Cross-references within a section are preserved.

**Relevance** — The LLM navigates to the section most likely to *contain* the answer, not the section most *similar* to the query. For structured documents this is a meaningful difference.

**Explainability** — Every query produces a `nodePath` — the exact sequence of sections the retrieval traversed. You can see "the answer came from Results → Q3 Performance → pages 59-70" which is impossible with vector RAG.

**Cost** — No embedding model is required. The tree is built once. Navigation uses lightweight LLM calls on short prompts (titles and one-line summaries only, not full content).

---

## Head-to-Head Comparison

| | Traditional RAG | Vectorless RAG |
|---|---|---|
| Indexing strategy | Split by token count | Split by document structure |
| Retrieval mechanism | Cosine similarity search | LLM reasoning + tree navigation |
| Vector database | ✅ Required | ❌ Not required |
| Embedding model | ✅ Required at index + query time | ❌ Not required |
| Explainability | ❌ No visibility into why chunks were chosen | ✅ Full navigation path returned |
| Cross-section context | ❌ Chunks are isolated | ✅ Sections are structurally aware |
| Best suited for | General-purpose, short documents | Structured documents with clear hierarchy |
| Cold start cost | Higher — embed all chunks | Lower — one LLM call to build tree |

---

## Multi-LLM Support

All three providers are configured simultaneously. Switching requires zero business logic change:

| Provider | Model | Use Case |
|---|---|---|
| Ollama | llama3.2 | Local dev, no API cost, Apple Silicon recommended |
| Gemini | gemini-2.0-flash | Free tier, fast for POC demos |
| Claude | claude-haiku-4-5 | Production quality, most capable |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 3.3.5 |
| AI Framework | Spring AI 1.1.4 |
| PDF Parsing | Spring AI PagePdfDocumentReader (PDFBox) |
| Vector Store | pgvector (PostgreSQL extension) |
| Local LLM | Ollama (Docker) |
| Tree Persistence | Jackson JSON + disk |
| Java | 21 |

---

## How to Run

```bash
# 1. Start infrastructure
docker-compose up -d
docker exec -it ollama ollama pull llama3.2
docker exec -it pgvector psql -U postgres -d vectorrag \
  -c "CREATE EXTENSION IF NOT EXISTS vector;"

# 2. Run application
mvn spring-boot:run

# 3. Index a document
curl -X POST "http://localhost:8080/api/rag/index?pdfPath=/path/to/doc.pdf"

# 4. Query vectorless pipeline
curl "http://localhost:8080/api/rag/vectorless/query?query=what+is+the+refund+policy&documentId=doc"

# 5. Benchmark both pipelines
curl "http://localhost:8080/api/rag/benchmark?query=what+is+revenue+for+Q3&documentId=doc"
```

---

## Sample Benchmark Response

```json
{
  "query": "What are the key safety evaluations?",
  "vectorless_rag": {
    "answer": "The model underwent RLHF-based safety training including...",
    "pagesRetrieved": 3,
    "nodePath": ["Safety & Alignment", "Evaluation Methods", "Red Teaming"],
    "totalTimeMs": 1840,
    "vectorDbUsed": false,
    "explainable": true
  },
  "vector_rag": {
    "answer": "Safety evaluations included...",
    "pagesRetrieved": 5,
    "nodePath": [],
    "totalTimeMs": 620,
    "vectorDbUsed": true,
    "explainable": false
  }
}
```

The `nodePath` field is the differentiator — vectorless RAG shows exactly which sections were traversed. Vector RAG cannot provide this.

---

## Why This Matters

**Why build this in Java instead of using Python PageIndex?**

The Python PageIndex SDK handles everything in 3 lines — tree building, storage, and retrieval are hidden inside the library. This Java implementation required building each component from scratch: understanding the algorithm deeply, designing the data structures, and wiring it into Spring AI's retrieval contract. Production Java backends cannot call a Python sidecar for every query — native Spring AI integration means zero cross-language overhead and direct enterprise integration.

**What is the actual difference in retrieval quality?**

Vector RAG finds text that is semantically similar to the query. Vectorless RAG finds text that is structurally relevant — the LLM navigates to the section most likely to contain the answer. For structured documents like financial reports, technical specifications, and legal documents, this is a meaningful and measurable difference.

**How does this scale?**

The tree is built once at index time and cached on disk. Per-query cost is 1-3 lightweight LLM calls for navigation on short prompts — no embedding model, no vector DB on the retrieval path. The idempotent indexing design means the same document can be re-queried indefinitely without rebuilding the tree.

---

## Current Status

| Feature | Status |
|---|---|
| PDF ingestion and page extraction | ✅ Complete |
| LLM-driven hierarchical tree building | ✅ Complete |
| Tree persistence (disk + in-memory cache) | ✅ Complete |
| Recursive LLM tree navigation | ✅ Complete |
| Multi-LLM support (Ollama / Gemini / Claude) | ✅ Complete |
| REST API with benchmark endpoint | ✅ Complete |
| Vector RAG comparison pipeline | 🔄 In Progress |
| Quantitative benchmark metrics | 🔄 In Progress |