package io.github.dpgaharwal.pageindex.retrieve;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dpgaharwal.pageindex.model.DocumentEntry;
import io.github.dpgaharwal.pageindex.model.PageContent;
import io.github.dpgaharwal.pageindex.model.PageNode;
import io.github.dpgaharwal.pageindex.store.DocumentStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implements the three retrieval methods matching Python's retrieve.py exactly:
 * get_document(), get_document_structure(), get_page_content().
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentRetrieval {

    private final DocumentStore store;
    private final ObjectMapper objectMapper;

    /**
     * Get document metadata (no structure, no pages).
     * Mirrors get_document() from retrieve.py.
     */
    public String getDocument(String docId) {
        Optional<DocumentEntry> opt = store.get(docId);
        if (opt.isEmpty()) return "{\"error\": \"Document not found: " + docId + "\"}";

        DocumentEntry entry = opt.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("doc_id", entry.getId());
        result.put("doc_name", entry.getDocName());
        result.put("doc_description", entry.getDocDescription());
        result.put("type", entry.getType());
        result.put("status", "indexed");
        if (entry.getPageCount() != null) result.put("page_count", entry.getPageCount());
        if (entry.getLineCount() != null) result.put("line_count", entry.getLineCount());

        return serialize(result);
    }

    /**
     * Get the document tree structure (without text fields).
     * Mirrors get_document_structure() from retrieve.py.
     */
    public String getDocumentStructure(String docId) {
        Optional<DocumentEntry> opt = store.get(docId);
        if (opt.isEmpty()) return "{\"error\": \"Document not found: " + docId + "\"}";

        DocumentEntry entry = opt.get();
        store.ensureLoaded(docId);

        List<PageNode> structure = entry.getStructure();
        if (structure == null) return "{\"error\": \"Structure not loaded for: " + docId + "\"}";

        // Remove text fields before returning (mirrors remove_structure_text)
        List<PageNode> clean = deepCopyWithoutText(structure);
        return serialize(clean);
    }

    /**
     * Get page content for a given page range string.
     * Accepts: "5-7" (range), "3,8" (list), "12" (single).
     * Mirrors get_page_content() from retrieve.py.
     */
    public String getPageContent(String docId, String pagesExpr) {
        Optional<DocumentEntry> opt = store.get(docId);
        if (opt.isEmpty()) return "{\"error\": \"Document not found: " + docId + "\"}";

        DocumentEntry entry = opt.get();
        store.ensureLoaded(docId);

        List<PageContent> allPages = entry.getPages();
        if (allPages == null || allPages.isEmpty()) {
            return "{\"error\": \"Pages not loaded for: " + docId + "\"}";
        }

        List<Integer> pageNumbers = parsePages(pagesExpr);
        Set<Integer> pageSet = new HashSet<>(pageNumbers);

        List<Map<String, Object>> results = allPages.stream()
                .filter(p -> pageSet.contains(p.getPage()))
                .sorted(Comparator.comparingInt(PageContent::getPage))
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("page", p.getPage());
                    m.put("content", p.getContent());
                    return m;
                })
                .collect(Collectors.toList());

        return serialize(results);
    }

    /**
     * Parse a page range expression into a sorted list of page numbers.
     * Supports: "5-7" → [5,6,7], "3,8" → [3,8], "12" → [12].
     * Mirrors _parse_pages() from retrieve.py.
     */
    public List<Integer> parsePages(String pagesExpr) {
        if (pagesExpr == null || pagesExpr.isBlank()) return List.of();
        List<Integer> pages = new ArrayList<>();
        for (String part : pagesExpr.split(",")) {
            part = part.trim();
            if (part.contains("-")) {
                String[] bounds = part.split("-", 2);
                try {
                    int start = Integer.parseInt(bounds[0].trim());
                    int end = Integer.parseInt(bounds[1].trim());
                    for (int i = start; i <= end; i++) pages.add(i);
                } catch (NumberFormatException ignored) {}
            } else {
                try { pages.add(Integer.parseInt(part)); } catch (NumberFormatException ignored) {}
            }
        }
        return pages.stream().sorted().distinct().collect(Collectors.toList());
    }

    private List<PageNode> deepCopyWithoutText(List<PageNode> nodes) {
        if (nodes == null) return null;
        return nodes.stream().map(n -> PageNode.builder()
                .nodeId(n.getNodeId()).title(n.getTitle())
                .startIndex(n.getStartIndex()).endIndex(n.getEndIndex())
                .summary(n.getSummary()).description(n.getDescription())
                .nodes(deepCopyWithoutText(n.getNodes()))
                .build()).collect(Collectors.toList());
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\": \"Serialization failed\"}";
        }
    }
}
