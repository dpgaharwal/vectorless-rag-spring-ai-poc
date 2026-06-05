package io.github.dpgaharwal.pageindex.toc;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.dpgaharwal.pageindex.model.TocItem;
import io.github.dpgaharwal.pageindex.util.JsonExtractor;
import io.github.dpgaharwal.pageindex.util.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Assigns physical_index values to TOC items by searching tagged page content.
 * Mirrors toc_index_extractor() and add_page_number_to_toc() from page_index.py.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TocIndexExtractor {

    private final LlmGateway llm;
    private final JsonExtractor json;

    private static final String EXTRACT_PROMPT = """
            Below are document pages tagged with their physical index numbers.
            For each TOC item, find the physical_index of the page where that section STARTS.

            Pages:
            %s

            TOC structure to fill in:
            %s

            Return a JSON array where each item has:
            - "structure": the dot-notation structure string (unchanged)
            - "title": the section title (unchanged)
            - "physical_index": the integer physical_index where this section starts (null if not found)

            JSON array only, no explanation:
            """;

    private static final String ADD_PAGE_PROMPT = """
            Below is a group of tagged document pages and a partial TOC structure.
            For TOC items that don't yet have a physical_index, determine which tagged page
            they start on and fill in the physical_index. Leave existing physical_index values unchanged.

            Pages:
            %s

            Current TOC (update physical_index where null):
            %s

            Return the complete JSON array with all items (updated and unchanged):
            """;

    /**
     * Given TOC structure (without physical indices) and tagged pages, add physical_index to each item.
     * Mirrors toc_index_extractor().
     */
    public List<TocItem> extractPhysicalIndices(List<TocItem> tocStructure, String taggedPageContent) {
        String tocJson = serializeItems(tocStructure);
        String prompt = String.format(EXTRACT_PROMPT, taggedPageContent, tocJson);
        String response = llm.call(prompt).getContent();
        List<TocItem> result = parseItems(response);
        if (result.isEmpty()) {
            log.warn("Physical index extraction returned empty result, using original structure");
            return tocStructure;
        }
        log.debug("Extracted physical indices for {} items", result.size());
        return result;
    }

    /**
     * For each page group, ask LLM to fill in start physical_index for TOC items.
     * Mirrors add_page_number_to_toc().
     */
    public List<TocItem> addPageNumbers(String taggedPageGroup, List<TocItem> currentStructure) {
        String tocJson = serializeItems(currentStructure);
        String prompt = String.format(ADD_PAGE_PROMPT, taggedPageGroup, tocJson);
        String response = llm.call(prompt).getContent();
        List<TocItem> updated = parseItems(response);
        if (updated.isEmpty()) return currentStructure;
        return updated;
    }

    private List<TocItem> parseItems(String response) {
        String cleaned = json.extractJsonString(response);
        try {
            List<TocItem> items = json.extractAs(cleaned, new TypeReference<List<TocItem>>() {});
            return items != null ? items : new ArrayList<>();
        } catch (Exception e) {
            log.warn("Could not parse TocItems: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String serializeItems(List<TocItem> items) {
        try {
            List<Map<String, Object>> simplified = items.stream().map(item -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("structure", item.getStructure());
                m.put("title", item.getTitle());
                m.put("physical_index", item.getPhysicalIndex());
                return m;
            }).collect(Collectors.toList());
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(simplified);
        } catch (Exception e) {
            return items.toString();
        }
    }
}
