package com.vectorlessrag.pipeline;

import com.vectorlessrag.retriever.TreeDocumentRetriever;
import com.vectorlessrag.tree.TreeBuilder;
import com.vectorlessrag.tree.TreeNode;
import com.vectorlessrag.tree.TreeStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIndexService {

  private final TreeBuilder treeBuilder;
  private final TreeStore treeStore;
  private final TreeDocumentRetriever treeDocumentRetriever;

  /** Full pipeline: PDF → parse pages → build tree → store → load into retriever */
  public String indexDocument(Path pdfPath) throws IOException {
    String documentId =
        pdfPath.getFileName().toString().replace(".pdf", "").replaceAll("[^a-zA-Z0-9_-]", "_");

    log.info("Indexing document: {}", documentId);

    // Check if already indexed — skip rebuild
    if (treeStore.exists(documentId)) {
      log.info("Tree already exists for: {}. Loading from store.", documentId);
      List<String> pages = extractPages(pdfPath);
      treeDocumentRetriever.loadDocument(documentId, pages);
      return documentId;
    }

    // Step 1 — Extract pages via Spring AI PdfDocumentReader
    List<String> pages = extractPages(pdfPath);
    log.info("Extracted {} pages from PDF", pages.size());

    // Step 2 — Build tree (LLM call)
    TreeNode root = treeBuilder.build(pages);

    // Step 3 — Store tree
    treeStore.save(documentId, root);

    // Step 4 — Load into retriever
    treeDocumentRetriever.loadDocument(documentId, pages);

    log.info("Document indexed successfully: {}", documentId);
    return documentId;
  }

  /** Switch active document without re-indexing. */
  public void activateDocument(String documentId, Path pdfPath) throws IOException {
    if (!treeStore.exists(documentId)) {
      throw new IllegalStateException("Document not indexed: " + documentId);
    }
    List<String> pages = extractPages(pdfPath);
    treeDocumentRetriever.loadDocument(documentId, pages);
    log.info("Activated document: {}", documentId);
  }

  /**
   * Uses Spring AI PagePdfDocumentReader — reads PDF page by page. Returns List<String> where index
   * = page number.
   */
  private List<String> extractPages(Path pdfPath) {
    PdfDocumentReaderConfig config =
        PdfDocumentReaderConfig.builder()
            .withPagesPerDocument(1) // one Document per page
            .build();

    PagePdfDocumentReader reader =
        new PagePdfDocumentReader(new FileSystemResource(pdfPath.toFile()), config);

    List<Document> documents = reader.read();

    return documents.stream().map(Document::getText).toList();
  }
}
