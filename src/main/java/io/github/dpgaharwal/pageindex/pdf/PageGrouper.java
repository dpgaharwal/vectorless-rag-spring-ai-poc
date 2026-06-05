package io.github.dpgaharwal.pageindex.pdf;

import io.github.dpgaharwal.pageindex.util.TokenCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups pages into token-bounded chunks for LLM processing.
 * Mirrors page_list_to_group_text() from page_index.py — replicates
 * the exact Python formula for balanced group sizes.
 */
@Component
@RequiredArgsConstructor
public class PageGrouper {

    private final TokenCounter tokenCounter;

    /**
     * Groups a slice of pages into token-bounded chunks.
     * Each chunk is a tagged string (using PhysicalIndexTagger).
     *
     * @param pages      full page list
     * @param startIndex 0-based index of first page to include
     * @param maxTokens  maximum tokens per chunk
     * @param overlap    number of pages to overlap between consecutive chunks
     * @return list of tagged page-group strings
     */
    public List<String> groupIntoChunks(List<PageData> pages,
                                         int startIndex,
                                         int maxTokens,
                                         int overlap) {
        List<PageData> slice = pages.subList(startIndex, pages.size());
        if (slice.isEmpty()) return List.of();

        // Calculate total tokens in the slice
        int totalTokens = slice.stream().mapToInt(PageData::getTokenCount).sum();
        if (totalTokens == 0) totalTokens = slice.stream()
                .mapToInt(p -> tokenCounter.count(p.getText())).sum();

        // Expected number of parts (Python: math.ceil(total_tokens / max_tokens))
        int expectedParts = (int) Math.ceil((double) totalTokens / maxTokens);
        if (expectedParts == 0) expectedParts = 1;

        // Balanced target per part — Python formula:
        // math.ceil(((num_tokens / expected_parts_num) + max_tokens) / 2)
        int targetPerPart = (int) Math.ceil(((double) totalTokens / expectedParts + maxTokens) / 2.0);

        List<String> groups = new ArrayList<>();
        int i = 0;
        while (i < slice.size()) {
            int groupTokens = 0;
            int groupStart = i;
            StringBuilder groupText = new StringBuilder();

            while (i < slice.size()) {
                PageData page = slice.get(i);
                int pageTokens = page.getTokenCount() > 0
                        ? page.getTokenCount()
                        : tokenCounter.count(page.getText());

                if (groupTokens + pageTokens > targetPerPart && groupTokens > 0) break;

                groupText.append(PhysicalIndexTagger.tagPage(startIndex + i, page.getText()));
                groupTokens += pageTokens;
                i++;
            }

            groups.add(groupText.toString());

            // Overlap: step back 'overlap' pages for the next chunk
            if (overlap > 0 && i < slice.size()) {
                i = Math.max(groupStart + 1, i - overlap);
            }
        }

        return groups;
    }

    /** Default overlap of 1 page. */
    public List<String> groupIntoChunks(List<PageData> pages, int startIndex, int maxTokens) {
        return groupIntoChunks(pages, startIndex, maxTokens, 1);
    }
}
