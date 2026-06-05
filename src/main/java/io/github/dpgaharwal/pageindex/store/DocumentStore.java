package io.github.dpgaharwal.pageindex.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dpgaharwal.pageindex.config.PageIndexProperties;
import io.github.dpgaharwal.pageindex.model.DocumentEntry;
import io.github.dpgaharwal.pageindex.model.DocumentResult;
import io.github.dpgaharwal.pageindex.model.PageContent;
import io.github.dpgaharwal.pageindex.model.PageNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-tier document persistence: ConcurrentHashMap in-memory cache + disk JSON files.
 * JSON keys match the Python PageIndex workspace format for cross-compatibility.
 * Mirrors _save_doc(), _load_workspace(), _save_meta() from client.py.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentStore {

    private final ObjectMapper objectMapper;
    private final PageIndexProperties properties;

    private final ConcurrentHashMap<String, DocumentEntry> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadWorkspace();
    }

    /**
     * Persist a document result and its pages to disk, register in cache.
     */
    public String save(DocumentResult result, List<PageContent> pages, String type, String filePath) {
        String docId = generateDocId(result.getDocName());

        DocumentEntry entry = DocumentEntry.builder()
                .id(docId)
                .type(type)
                .docName(result.getDocName())
                .docDescription(result.getDocDescription())
                .path(filePath)
                .pageCount("pdf".equals(type) ? pages.size() : null)
                .lineCount("md".equals(type) ? pages.size() : null)
                .structure(result.getStructure())
                .pages(pages)
                .build();

        cache.put(docId, entry);
        saveToDisk(docId, result, pages);
        log.info("Saved document '{}' as id={}", result.getDocName(), docId);
        return docId;
    }

    public Optional<DocumentEntry> get(String docId) {
        DocumentEntry entry = cache.get(docId);
        if (entry != null && entry.getStructure() == null) {
            ensureLoaded(docId);
        }
        return Optional.ofNullable(cache.get(docId));
    }

    public boolean exists(String docId) {
        return cache.containsKey(docId);
    }

    public Map<String, DocumentEntry> listAll() {
        return Collections.unmodifiableMap(cache);
    }

    public void delete(String docId) {
        cache.remove(docId);
        Path file = storeDir().resolve(docId + ".json");
        try { Files.deleteIfExists(file); } catch (IOException e) {
            log.warn("Could not delete store file for {}: {}", docId, e.getMessage());
        }
    }

    /** Lazy-load structure + pages from disk into memory. */
    public void ensureLoaded(String docId) {
        DocumentEntry entry = cache.get(docId);
        if (entry == null) return;
        if (entry.getStructure() != null) return;

        Path file = storeDir().resolve(docId + ".json");
        if (!Files.exists(file)) return;

        try {
            Map<String, Object> data = objectMapper.readValue(file.toFile(),
                    new TypeReference<Map<String, Object>>() {});

            @SuppressWarnings("unchecked")
            List<PageNode> structure = objectMapper.convertValue(
                    data.get("structure"), new TypeReference<List<PageNode>>() {});
            @SuppressWarnings("unchecked")
            List<PageContent> pages = objectMapper.convertValue(
                    data.get("pages"), new TypeReference<List<PageContent>>() {});

            entry.setStructure(structure);
            entry.setPages(pages);
        } catch (IOException e) {
            log.error("Could not load document {} from disk: {}", docId, e.getMessage());
        }
    }

    private void saveToDisk(String docId, DocumentResult result, List<PageContent> pages) {
        try {
            Files.createDirectories(storeDir());
            // Wire-compatible with Python PageIndex workspace JSON keys
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("doc_name", result.getDocName());
            data.put("doc_description", result.getDocDescription());
            data.put("structure", result.getStructure());
            data.put("pages", pages);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(storeDir().resolve(docId + ".json").toFile(), data);
        } catch (IOException e) {
            log.error("Could not save document {} to disk: {}", docId, e.getMessage());
        }
    }

    /** Scan store directory and populate cache with metadata (lazy — no structure loaded yet). */
    private void loadWorkspace() {
        Path dir = storeDir();
        if (!Files.exists(dir)) return;
        try {
            Files.list(dir).filter(p -> p.toString().endsWith(".json")).forEach(file -> {
                try {
                    Map<String, Object> data = objectMapper.readValue(file.toFile(),
                            new TypeReference<Map<String, Object>>() {});
                    String docId = file.getFileName().toString().replace(".json", "");
                    DocumentEntry entry = DocumentEntry.builder()
                            .id(docId)
                            .docName((String) data.get("doc_name"))
                            .docDescription((String) data.get("doc_description"))
                            .build();
                    cache.put(docId, entry);
                } catch (Exception e) {
                    log.warn("Could not read workspace file {}: {}", file, e.getMessage());
                }
            });
            log.info("Workspace loaded: {} documents", cache.size());
        } catch (IOException e) {
            log.warn("Could not scan workspace directory: {}", e.getMessage());
        }
    }

    private Path storeDir() {
        return Paths.get(properties.getStoreDir());
    }

    private String generateDocId(String docName) {
        String base = docName.toLowerCase()
                .replaceAll("[^a-z0-9]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        // Append timestamp suffix to avoid collisions
        return base + "_" + (System.currentTimeMillis() / 1000);
    }
}
