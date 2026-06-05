package io.github.dpgaharwal.pageindex;

import io.github.dpgaharwal.pageindex.config.PageIndexConfig;
import io.github.dpgaharwal.pageindex.config.PageIndexProperties;
import io.github.dpgaharwal.pageindex.features.NodeFeatureEnricher;
import io.github.dpgaharwal.pageindex.markdown.MarkdownIndexer;
import io.github.dpgaharwal.pageindex.markdown.MarkdownNode;
import io.github.dpgaharwal.pageindex.markdown.MarkdownParser;
import io.github.dpgaharwal.pageindex.model.*;
import io.github.dpgaharwal.pageindex.pdf.PageData;
import io.github.dpgaharwal.pageindex.pdf.PdfParser;
import io.github.dpgaharwal.pageindex.retrieve.DocumentRetrieval;
import io.github.dpgaharwal.pageindex.store.DocumentStore;
import io.github.dpgaharwal.pageindex.tree.TreeParser;
import io.github.dpgaharwal.pageindex.util.IndexingLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Primary Spring Bean — the only class library consumers interact with.
 * Mirrors PageIndexClient from client.py.
 *
 * <pre>
 * {@code
 * @Autowired PageIndexClient pageIndex;
 *
 * String docId = pageIndex.index(Path.of("report.pdf"));
 * String structure = pageIndex.getDocumentStructure(docId);
 * String content  = pageIndex.getPageContent(docId, "5-7");
 * }
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageIndexClient {

    private final PdfParser pdfParser;
    private final TreeParser treeParser;
    private final MarkdownIndexer markdownIndexer;
    private final MarkdownParser markdownParser;
    private final NodeFeatureEnricher enricher;
    private final DocumentStore documentStore;
    private final DocumentRetrieval retrieval;
    private final PageIndexProperties properties;
    private final IndexingLogger indexingLogger;

    // ──────────────────────────────────────────────────────────────────────────
    // INDEXING
    // ──────────────────────────────────────────────────────────────────────────

    /** Index a file using AUTO mode (detect from extension). */
    public String index(Path filePath) {
        return index(filePath, IndexMode.AUTO, null);
    }

    /** Index a file with explicit mode. */
    public String index(Path filePath, IndexMode mode) {
        return index(filePath, mode, null);
    }

    /**
     * Index a file with optional per-call config overrides.
     * Returns the document ID for subsequent retrieval calls.
     * Mirrors PageIndexClient.index().
     */
    public String index(Path filePath, IndexMode mode, PageIndexConfig configOverrides) {
        PageIndexConfig config = PageIndexConfig.from(properties);
        if (configOverrides != null) config = config.mergeWith(configOverrides);

        IndexMode resolvedMode = resolveMode(filePath, mode);
        String docName = filePath.getFileName().toString();

        try (var session = indexingLogger.startSession(docName)) {
            session.info("Starting indexing: " + filePath);

            try {
                return switch (resolvedMode) {
                    case PDF -> indexPdf(filePath, config, session);
                    case MARKDOWN -> indexMarkdown(filePath, config, session);
                    default -> throw new IllegalArgumentException("Cannot determine file type for: " + filePath);
                };
            } catch (IOException e) {
                session.error("IO error during indexing: " + e.getMessage());
                throw new RuntimeException("Failed to index file: " + filePath, e);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // RETRIEVAL (mirrors retrieve.py)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Get document metadata: doc_id, doc_name, doc_description, type, status, page_count.
     * Mirrors get_document() from retrieve.py.
     */
    public String getDocument(String docId) {
        return retrieval.getDocument(docId);
    }

    /**
     * Get the document's hierarchical tree structure (without raw text).
     * Mirrors get_document_structure() from retrieve.py.
     */
    public String getDocumentStructure(String docId) {
        return retrieval.getDocumentStructure(docId);
    }

    /**
     * Get raw page content for a page range expression.
     * Examples: "5-7", "3,8", "12"
     * Mirrors get_page_content() from retrieve.py.
     */
    public String getPageContent(String docId, String pages) {
        return retrieval.getPageContent(docId, pages);
    }

    /** Get the full DocumentEntry with lazy-loaded structure and pages. */
    public DocumentEntry getDocumentEntry(String docId) {
        if (docId == null) return null;
        documentStore.ensureLoaded(docId);
        return documentStore.get(docId).orElse(null);
    }

    /** Get parsed structure as Java objects instead of JSON string. */
    public List<PageNode> getDocumentStructureParsed(String docId) {
        DocumentEntry entry = getDocumentEntry(docId);
        return entry != null ? entry.getStructure() : List.of();
    }

    /** Get all indexed documents as a map of docId → DocumentEntry. */
    public Map<String, DocumentEntry> getAllDocuments() {
        return documentStore.listAll();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // INTERNAL
    // ──────────────────────────────────────────────────────────────────────────

    private String indexPdf(Path filePath, PageIndexConfig config,
                             IndexingLogger.IndexingSession session) {
        session.info("Parsing PDF pages");
        List<PageData> pages = pdfParser.parse(filePath);
        session.info("Parsed " + pages.size() + " pages");

        session.info("Building tree structure");
        List<PageNode> tree = treeParser.parse(pages, config);

        // Optional features
        if (Boolean.TRUE.equals(config.getAddNodeId())) {
            enricher.writeNodeIds(tree);
        }
        if (Boolean.TRUE.equals(config.getAddNodeText())) {
            enricher.addNodeText(tree, pages);
        }
        if (Boolean.TRUE.equals(config.getAddNodeSummary())) {
            session.info("Generating node summaries");
            enricher.generateSummaries(tree, pages);
        }

        String description = null;
        if (Boolean.TRUE.equals(config.getAddDocDescription())) {
            session.info("Generating document description");
            description = enricher.generateDocDescription(tree);
        }

        if (!Boolean.TRUE.equals(config.getAddNodeText())) {
            enricher.removeNodeText(tree);
        }

        // Convert pages for storage
        List<PageContent> pageContents = pages.stream()
                .map(p -> new PageContent(p.getPageNumber(), p.getText()))
                .collect(Collectors.toList());

        DocumentResult result = DocumentResult.builder()
                .docName(filePath.getFileName().toString())
                .docDescription(description)
                .structure(tree)
                .build();

        String docId = documentStore.save(result, pageContents, "pdf", filePath.toString());
        session.info("Indexed successfully as " + docId);
        return docId;
    }

    private String indexMarkdown(Path filePath, PageIndexConfig config,
                                   IndexingLogger.IndexingSession session) throws IOException {
        session.info("Indexing Markdown: " + filePath.getFileName());
        DocumentResult result = markdownIndexer.index(filePath, config, false);

        // Re-parse to get raw nodes for page content storage
        List<MarkdownNode> nodes = markdownParser.parse(filePath);
        List<PageContent> pages = nodes.stream()
                .map(n -> new PageContent(n.getLineNum(), n.getText() != null ? n.getText() : ""))
                .collect(Collectors.toList());

        String docId = documentStore.save(result, pages, "md", filePath.toString());
        session.info("Indexed successfully as " + docId);
        return docId;
    }

    private IndexMode resolveMode(Path filePath, IndexMode mode) {
        if (mode != IndexMode.AUTO) return mode;
        String name = filePath.getFileName().toString().toLowerCase();
        if (name.endsWith(".pdf")) return IndexMode.PDF;
        if (name.endsWith(".md") || name.endsWith(".markdown")) return IndexMode.MARKDOWN;
        throw new IllegalArgumentException("Cannot determine file type for: " + filePath);
    }
}
