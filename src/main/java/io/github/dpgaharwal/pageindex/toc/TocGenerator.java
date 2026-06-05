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

/**
 * Generates a hierarchical tree structure from document content when no TOC exists.
 * Mirrors generate_toc_init() and generate_toc_continue() from page_index.py.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TocGenerator {

    private final LlmGateway llm;
    private final JsonExtractor json;

    private static final String INIT_PROMPT = """
            Analyze the following document pages (tagged with physical index numbers) and create a hierarchical structure.

            Rules:
            - Group related pages into logical sections (chapters, major topics, subsections)
            - Use dot-notation for hierarchy: "1", "1.1", "1.1.1"
            - Each leaf section should contain no more than 10 pages
            - Assign the physical_index of the FIRST page where each section starts
            - Cover ALL pages provided

            Pages:
            %s

            Return ONLY a JSON array where each item has:
            - "structure": dot-notation string (e.g. "1", "1.2")
            - "title": descriptive section title
            - "physical_index": integer — the physical_index tag where this section begins

            JSON array:
            """;

    private static final String CONTINUE_PROMPT = """
            Continue building the document structure for the remaining pages.
            The structure so far is shown below. Add new sections for the remaining pages.
            Do NOT repeat existing sections. Continue the numbering from where it left off.

            Existing structure:
            %s

            Remaining pages:
            %s

            Return ONLY a JSON array of the NEW sections to append:
            """;

    /**
     * Generate initial tree structure from first group of pages.
     * Mirrors generate_toc_init().
     */
    public List<TocItem> generateInit(String taggedPageGroup) {
        String prompt = String.format(INIT_PROMPT, taggedPageGroup);
        String response = llm.call(prompt).getContent();
        List<TocItem> items = parseItems(response);
        log.debug("Generated initial structure: {} items", items.size());
        return items;
    }

    /**
     * Continue tree structure for the next batch of pages.
     * Mirrors generate_toc_continue().
     */
    public List<TocItem> generateContinue(List<TocItem> previousStructure, String taggedPageGroup) {
        String prevJson = serializeItems(previousStructure);
        String prompt = String.format(CONTINUE_PROMPT, prevJson, taggedPageGroup);
        String response = llm.call(prompt).getContent();
        List<TocItem> newItems = parseItems(response);
        log.debug("Generated {} additional structure items", newItems.size());
        return newItems;
    }

    private List<TocItem> parseItems(String response) {
        String cleaned = json.extractJsonString(response);
        try {
            List<TocItem> items = json.extractAs(cleaned, new TypeReference<List<TocItem>>() {});
            return items != null ? items : new ArrayList<>();
        } catch (Exception e) {
            log.warn("Could not parse generated structure: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String serializeItems(List<TocItem> items) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(items);
        } catch (Exception e) {
            return "[]";
        }
    }
}
