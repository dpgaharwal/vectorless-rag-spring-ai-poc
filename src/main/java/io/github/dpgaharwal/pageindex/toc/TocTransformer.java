package io.github.dpgaharwal.pageindex.toc;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.dpgaharwal.pageindex.model.TocItem;
import io.github.dpgaharwal.pageindex.util.JsonExtractor;
import io.github.dpgaharwal.pageindex.util.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts raw TOC text into a structured list of TocItems via LLM.
 * Includes retry loop for incomplete multi-page TOC responses.
 * Mirrors toc_transformer() and extract_toc_content() from page_index.py.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TocTransformer {

    private final LlmGateway llm;
    private final JsonExtractor json;

    private static final int MAX_CONTINUATION_ATTEMPTS = 5;

    private static final String TRANSFORM_PROMPT = """
            Convert the following Table of Contents into a structured JSON array.
            Each entry must have:
            - "structure": hierarchical dot-notation (e.g. "1", "1.2", "1.2.3")
            - "title": the section title
            - "page": the page number as integer (null if not present)

            Rules:
            - Preserve the exact hierarchy from the TOC
            - Do not add or remove entries
            - Output ONLY the JSON array, no explanation

            TOC:
            %s

            JSON array:
            """;

    private static final String CONTINUE_PROMPT = """
            Continue the JSON array from where you left off. Do not repeat entries already output.
            Output only the remaining entries as a valid JSON array continuation.
            """;

    private static final String EXTRACT_PROMPT = """
            Extract the Table of Contents from the following text. Clean up any OCR artifacts.
            Return ONLY the TOC entries, one per line, in the format: "title ... page_number"
            Remove any headers like "Contents" or "Table of Contents".

            Text:
            %s

            Cleaned TOC:
            """;

    /**
     * Transform raw TOC text into a structured list of TocItems.
     * Handles multi-turn continuation for long TOCs.
     * Mirrors toc_transformer().
     */
    public List<TocItem> transform(String tocContent) {
        String prompt = String.format(TRANSFORM_PROMPT, tocContent);
        String response = llm.call(prompt).getContent();
        List<TocItem> items = parseItems(response);

        // Continuation loop for incomplete responses
        for (int attempt = 0; attempt < MAX_CONTINUATION_ATTEMPTS; attempt++) {
            if (isTransformationComplete(tocContent, items)) break;
            log.debug("TOC transformation incomplete, continuing (attempt {})", attempt + 1);
            List<Message> messages = llm.continuationMessages(prompt, response, CONTINUE_PROMPT);
            String continuation = llm.call(messages).getContent();
            List<TocItem> additional = parseItems(continuation);
            if (additional.isEmpty()) break;
            items = merge(items, additional);
            response = continuation;
        }

        log.info("TOC transformed: {} items", items.size());
        return items;
    }

    /**
     * Extract raw TOC content from mixed page text (removes dots, cleans formatting).
     * Mirrors extract_toc_content().
     */
    public String extractContent(String rawContent) {
        String prompt = String.format(EXTRACT_PROMPT, rawContent);
        String response = llm.call(prompt).getContent();

        // Continuation if extraction seems incomplete
        for (int attempt = 0; attempt < MAX_CONTINUATION_ATTEMPTS; attempt++) {
            if (response.trim().length() > 20) break;
            List<Message> messages = llm.continuationMessages(prompt, response, "Continue extracting the remaining TOC entries:");
            response = llm.call(messages).getContent();
        }
        return response.trim();
    }

    /**
     * Check if the transformed items roughly cover the original TOC content.
     * Mirrors check_if_toc_transformation_is_complete().
     */
    public boolean isTransformationComplete(String rawContent, List<TocItem> items) {
        if (items.isEmpty()) return false;
        // Heuristic: each item title should appear somewhere in the raw content
        long matched = items.stream()
                .filter(item -> item.getTitle() != null &&
                        rawContent.toLowerCase().contains(item.getTitle().toLowerCase().substring(0, Math.min(5, item.getTitle().length()))))
                .count();
        return matched >= items.size() * 0.8;
    }

    private List<TocItem> parseItems(String response) {
        if (response == null || response.isBlank()) return new ArrayList<>();
        String cleaned = json.extractJsonString(response);
        try {
            List<TocItem> items = json.extractAs(cleaned, new TypeReference<List<TocItem>>() {});
            return items != null ? items : new ArrayList<>();
        } catch (Exception e) {
            log.warn("Could not parse TOC items from: {}", cleaned.substring(0, Math.min(100, cleaned.length())));
            return new ArrayList<>();
        }
    }

    private List<TocItem> merge(List<TocItem> existing, List<TocItem> additional) {
        List<TocItem> merged = new ArrayList<>(existing);
        for (TocItem item : additional) {
            boolean duplicate = existing.stream()
                    .anyMatch(e -> e.getTitle() != null && e.getTitle().equals(item.getTitle())
                            && e.getStructure() != null && e.getStructure().equals(item.getStructure()));
            if (!duplicate) merged.add(item);
        }
        return merged;
    }
}
