package com.vectorlessrag.pipeline;

import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnBean(VectorStore.class)
@RequiredArgsConstructor
public class VectorRagPipeline {

    private final VectorStore vectorStore;

    private static final int CHUNK_SIZE = 500;
    private static final int TOP_K = 5;

    /**
     * Index PDF into pgvector — chunk → embed → store.
     * Idempotent by document metadata filter.
     */
    public void indexDocument(Path pdfPath, String documentId) {
        log.info("Vector RAG — indexing document: {}", documentId);

        // Read PDF
        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPagesPerDocument(1)
                .build();

        PagePdfDocumentReader reader = new PagePdfDocumentReader(
                new FileSystemResource(pdfPath.toFile()), config);

        List<Document> pages = reader.read();

        // Add documentId to metadata for filtering
        pages.forEach(doc ->
                doc.getMetadata().put("documentId", documentId));

        // Chunk — split pages into smaller token chunks
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(CHUNK_SIZE)
                .withMinChunkSizeChars(50)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .build();
        List<Document> chunks = splitter.apply(pages);

        log.info("Vector RAG — {} pages split into {} chunks", pages.size(), chunks.size());

        // Embed + store in pgvector
        vectorStore.add(chunks);

        log.info("Vector RAG — indexed {} chunks into pgvector", chunks.size());
    }

    /**
     * Retrieve top-K similar chunks for a query.
     */
    public List<Document> retrieve(String query, String documentId) {
        log.info("Vector RAG — retrieving for query: {}", query);

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(TOP_K)
                .filterExpression("documentId == '" + documentId + "'")
                .build();

        List<Document> results = vectorStore.similaritySearch(request);
        log.info("Vector RAG — retrieved {} chunks", results.size());
        return results;
    }
}