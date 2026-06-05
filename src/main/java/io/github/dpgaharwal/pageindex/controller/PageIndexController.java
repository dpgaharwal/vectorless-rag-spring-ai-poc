package io.github.dpgaharwal.pageindex.controller;

import io.github.dpgaharwal.pageindex.PageIndexClient;
import io.github.dpgaharwal.pageindex.model.IndexMode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API exposing PageIndexClient operations.
 * All endpoints work with document IDs returned by /index.
 */
@RestController
@RequestMapping("/api/pageindex")
@RequiredArgsConstructor
public class PageIndexController {

    private final PageIndexClient pageIndexClient;

    /**
     * POST /api/pageindex/index?filePath=/abs/path/to/file.pdf&mode=AUTO
     * Returns: { "status": "success", "documentId": "...", "indexingTimeMs": 1234 }
     */
    @PostMapping("/index")
    public ResponseEntity<Map<String, Object>> index(
            @RequestParam String filePath,
            @RequestParam(defaultValue = "AUTO") IndexMode mode) {
        long start = System.currentTimeMillis();
        try {
            String docId = pageIndexClient.index(Path.of(filePath), mode);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "success");
            resp.put("documentId", docId);
            resp.put("indexingTimeMs", System.currentTimeMillis() - start);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error", "message", e.getMessage()));
        }
    }

    /**
     * GET /api/pageindex/document/{docId}
     * Returns document metadata JSON.
     */
    @GetMapping("/document/{docId}")
    public ResponseEntity<String> getDocument(@PathVariable String docId) {
        return ResponseEntity.ok(pageIndexClient.getDocument(docId));
    }

    /**
     * GET /api/pageindex/document/{docId}/structure
     * Returns the hierarchical tree structure JSON.
     */
    @GetMapping("/document/{docId}/structure")
    public ResponseEntity<String> getStructure(@PathVariable String docId) {
        return ResponseEntity.ok(pageIndexClient.getDocumentStructure(docId));
    }

    /**
     * GET /api/pageindex/document/{docId}/pages?pages=5-7
     * Returns raw page content for the given range expression.
     */
    @GetMapping("/document/{docId}/pages")
    public ResponseEntity<String> getPageContent(
            @PathVariable String docId,
            @RequestParam String pages) {
        return ResponseEntity.ok(pageIndexClient.getPageContent(docId, pages));
    }

    /**
     * GET /api/pageindex/documents
     * List all indexed documents.
     */
    @GetMapping("/documents")
    public ResponseEntity<Map<String, Object>> listDocuments() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documents", pageIndexClient.getAllDocuments());
        result.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(result);
    }
}
