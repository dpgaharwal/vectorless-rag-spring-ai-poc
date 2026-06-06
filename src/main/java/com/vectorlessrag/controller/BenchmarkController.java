package com.vectorlessrag.controller;

import com.vectorlessrag.pipeline.DocumentIndexService;
import com.vectorlessrag.pipeline.VectorRagPipeline;
import com.vectorlessrag.retriever.TreeDocumentRetriever;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class BenchmarkController {

  private final DocumentIndexService documentIndexService;
  private final TreeDocumentRetriever treeDocumentRetriever;
  private final ChatClient chatClient;

  @Nullable
  @Autowired(required = false)
  private VectorRagPipeline vectorRagPipeline;

  // 1. Index a PDF
  @PostMapping("/index")
  public ResponseEntity<Map<String, Object>> indexDocument(@RequestParam String pdfPath) {
    try {
      Path path = Paths.get(pdfPath);
      Instant start = Instant.now();
      String documentId = documentIndexService.indexDocument(path);
      long ms = Duration.between(start, Instant.now()).toMillis();

      return ResponseEntity.ok(
          Map.of(
              "status", "success",
              "documentId", documentId,
              "indexingTimeMs", ms));
    } catch (Exception e) {
      log.error("Indexing failed: {}", e.getMessage());
      return ResponseEntity.internalServerError()
          .body(Map.of("status", "error", "message", e.getMessage()));
    }
  }

  // 2. Vectorless RAG query
  @GetMapping("/vectorless/query")
  public ResponseEntity<Map<String, Object>> vectorlessQuery(
      @RequestParam String query, @RequestParam String documentId) {
    try {

      treeDocumentRetriever.activeDocument(documentId);

      Instant start = Instant.now();

      // Retrieve relevant pages via tree navigation
      List<Document> retrieved =
          treeDocumentRetriever.retrieve(Query.builder().text(query).build());
      long retrievalMs = Duration.between(start, Instant.now()).toMillis();

      // Build context from retrieved pages
      String context =
          retrieved.stream().map(Document::getText).reduce("", (a, b) -> a + "\n\n" + b);

      // Generate answer
      Instant genStart = Instant.now();
      String answer = generateAnswer(query, context);
      long generationMs = Duration.between(genStart, Instant.now()).toMillis();

      // Extract node path for explainability
      List<Map<String, Object>> sourceNodes =
          retrieved.stream()
              .map(
                  doc ->
                      Map.of(
                          "page", doc.getMetadata().getOrDefault("page", "?"),
                          "node_id", doc.getMetadata().getOrDefault("node_id", "?"),
                          "node_title", doc.getMetadata().getOrDefault("node_title", "?")))
              .distinct()
              .toList();

      return ResponseEntity.ok(
          Map.of(
              "pipeline", "vectorless-rag",
              "query", query,
              "answer", answer,
              "pagesRetrieved", retrieved.size(),
              "sourceNodes", sourceNodes,
              "retrievalTimeMs", retrievalMs,
              "generationTimeMs", generationMs,
              "totalTimeMs", retrievalMs + generationMs));

    } catch (Exception e) {
      log.error("Vectorless query failed: {}", e.getMessage());
      return ResponseEntity.internalServerError()
          .body(Map.of("status", "error", "message", e.getMessage()));
    }
  }

  // 3. Benchmark — both pipelines, same query
  @GetMapping("/benchmark")
  public ResponseEntity<Map<String, Object>> benchmark(
          @RequestParam String query,
          @RequestParam String documentId) {
    try {
      // ── Vectorless RAG ──
      treeDocumentRetriever.activeDocument(documentId);
      Instant v1Start = Instant.now();
      List<Document> vectorlessPages = treeDocumentRetriever.retrieve(
              Query.builder().text(query).build());
      String vectorlessContext = vectorlessPages.stream()
              .map(Document::getText)
              .reduce("", (a, b) -> a + "\n\n" + b);
      String vectorlessAnswer = generateAnswer(query, vectorlessContext);
      long vectorlessMs = Duration.between(v1Start, Instant.now()).toMillis();

      List<String> nodePath = vectorlessPages.stream()
              .map(d -> d.getMetadata().getOrDefault("node_title", "?").toString())
              .distinct()
              .toList();

      // ── Vector RAG ──
      Instant v2Start = Instant.now();
      List<Document> vectorChunks = vectorRagPipeline != null
              ? vectorRagPipeline.retrieve(query, documentId) : List.of();
      String vectorContext = vectorChunks.stream()
              .map(Document::getText)
              .reduce("", (a, b) -> a + "\n\n" + b);
      String vectorAnswer = vectorRagPipeline != null
              ? generateAnswer(query, vectorContext) : "pgvector not configured.";
      long vectorMs = Duration.between(v2Start, Instant.now()).toMillis();

      return ResponseEntity.ok(Map.of(
              "query", query,
              "vectorless_rag", Map.of(
                      "answer", vectorlessAnswer,
                      "pagesRetrieved", vectorlessPages.size(),
                      "nodePath", nodePath,
                      "totalTimeMs", vectorlessMs,
                      "vectorDbUsed", false,
                      "explainable", true
              ),
              "vector_rag", Map.of(
                      "answer", vectorAnswer,
                      "chunksRetrieved", vectorChunks.size(),
                      "nodePath", List.of(),
                      "totalTimeMs", vectorMs,
                      "vectorDbUsed", true,
                      "explainable", false
              )
      ));

    } catch (Exception e) {
      log.error("Benchmark failed: {}", e.getMessage());
      return ResponseEntity.internalServerError()
              .body(Map.of("status", "error", "message", e.getMessage()));
    }
  }

  private String generateAnswer(String query, String context) {
    if (context.isBlank()) {
      return "No relevant content found for this query.";
    }

    String prompt =
        """
                You are a helpful assistant. Answer the question using ONLY the provided context.
                If the answer is not in the context, say "Not found in document."

                Context:
                %s

                Question: %s

                Answer:
                """
            .formatted(context, query);

    return chatClient.prompt().user(prompt).call().content();
  }
}
