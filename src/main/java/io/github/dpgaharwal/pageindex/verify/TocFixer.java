package io.github.dpgaharwal.pageindex.verify;

import io.github.dpgaharwal.pageindex.model.TocItem;
import io.github.dpgaharwal.pageindex.pdf.PageData;
import io.github.dpgaharwal.pageindex.pdf.PhysicalIndexTagger;
import io.github.dpgaharwal.pageindex.util.JsonExtractor;
import io.github.dpgaharwal.pageindex.util.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Fixes incorrect physical_index assignments by searching nearby pages.
 * Mirrors single_toc_item_index_fixer(), fix_incorrect_toc(), and
 * fix_incorrect_toc_with_retries() from page_index.py.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TocFixer {

    private final LlmGateway llm;
    private final JsonExtractor json;
    private final TocVerifier verifier;

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int SEARCH_WINDOW = 5;

    private static final String FIX_PROMPT = """
            The section titled "%s" was expected on page %d but was not found there.
            Search the following pages (tagged with physical_index) and find the correct page where this section starts.

            Pages to search:
            %s

            Respond ONLY with JSON: {"thinking": "<brief reasoning>", "physical_index": <integer or null>}
            """;

    /**
     * Fix a single incorrect TOC item by searching a window of nearby pages.
     * Mirrors single_toc_item_index_fixer().
     */
    public CompletableFuture<FixResult> fixSingleItem(TocItem incorrectItem,
                                                        List<TocItem> allItems,
                                                        Set<Integer> incorrectIndices,
                                                        List<PageData> pages,
                                                        int startIndex,
                                                        int listIndex) {
        return CompletableFuture.supplyAsync(() -> {
            // Find the search window: previous correct index → next correct index
            int prevCorrect = startIndex;
            int nextCorrect = pages.size() - 1;

            for (int i = listIndex - 1; i >= 0; i--) {
                if (!incorrectIndices.contains(i) && allItems.get(i).getPhysicalIndex() != null) {
                    prevCorrect = allItems.get(i).getPhysicalIndex();
                    break;
                }
            }
            for (int i = listIndex + 1; i < allItems.size(); i++) {
                if (!incorrectIndices.contains(i) && allItems.get(i).getPhysicalIndex() != null) {
                    nextCorrect = allItems.get(i).getPhysicalIndex();
                    break;
                }
            }

            // Build tagged pages for the search window (with small buffer)
            int searchStart = Math.max(prevCorrect, startIndex);
            int searchEnd = Math.min(nextCorrect + SEARCH_WINDOW, pages.size() - 1);
            String taggedPages = PhysicalIndexTagger.tagPages(pages, searchStart, searchEnd);

            String prompt = String.format(FIX_PROMPT, incorrectItem.getTitle(),
                    incorrectItem.getPhysicalIndex() != null ? incorrectItem.getPhysicalIndex() : -1,
                    taggedPages);

            String response = llm.call(prompt).getContent();
            var node = json.extract(response);

            Integer newIndex = null;
            if (node.has("physical_index") && !node.get("physical_index").isNull()) {
                newIndex = node.get("physical_index").asInt();
                // Validate bounds
                if (newIndex < startIndex || newIndex >= pages.size()) newIndex = null;
            }

            boolean isValid = newIndex != null;
            return new FixResult(listIndex, incorrectItem.getTitle(), newIndex, isValid);
        });
    }

    /**
     * Fix all incorrect items concurrently.
     * Mirrors fix_incorrect_toc().
     */
    public FixBatchResult fix(List<TocItem> tocItems, List<PageData> pages,
                               List<TitleCheckResult> incorrectResults, int startIndex) {
        Set<Integer> incorrectIndices = incorrectResults.stream()
                .map(TitleCheckResult::getListIndex)
                .collect(Collectors.toSet());

        List<Supplier<CompletableFuture<FixResult>>> tasks = incorrectResults.stream()
                .map(result -> (Supplier<CompletableFuture<FixResult>>) () ->
                        fixSingleItem(tocItems.get(result.getListIndex()), tocItems,
                                incorrectIndices, pages, startIndex, result.getListIndex()))
                .collect(Collectors.toList());

        List<FixResult> fixResults = llm.gatherAsync(tasks);

        // Apply fixes
        List<TocItem> updatedItems = new ArrayList<>(tocItems);
        for (FixResult fix : fixResults) {
            if (fix == null) continue;
            TocItem original = tocItems.get(fix.getListIndex());
            updatedItems.set(fix.getListIndex(), TocItem.builder()
                    .structure(original.getStructure()).title(original.getTitle())
                    .page(original.getPage()).physicalIndex(fix.getPhysicalIndex())
                    .appearStart(original.getAppearStart()).build());
        }

        // Re-verify fixed items
        List<TitleCheckResult> stillInvalid = new ArrayList<>();
        for (FixResult fix : fixResults) {
            if (fix == null || !fix.isValid()) {
                if (fix != null) {
                    stillInvalid.add(new TitleCheckResult(fix.getListIndex(), "no", fix.getTitle(), fix.getPhysicalIndex()));
                }
            }
        }

        return new FixBatchResult(updatedItems, stillInvalid);
    }

    /**
     * Retry fix loop until all items are correct or max attempts reached.
     * Mirrors fix_incorrect_toc_with_retries().
     */
    public List<TocItem> fixWithRetries(List<TocItem> tocItems, List<PageData> pages,
                                         List<TitleCheckResult> incorrectResults,
                                         int startIndex) {
        List<TocItem> current = tocItems;
        List<TitleCheckResult> remaining = incorrectResults;

        for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
            if (remaining.isEmpty()) break;
            log.info("Fix attempt {}/{}: {} items remaining", attempt + 1, MAX_RETRY_ATTEMPTS, remaining.size());
            FixBatchResult result = fix(current, pages, remaining, startIndex);
            current = result.getUpdatedItems();
            remaining = result.getStillInvalid();
        }

        if (!remaining.isEmpty()) {
            log.warn("{} TOC items could not be fixed after {} attempts", remaining.size(), MAX_RETRY_ATTEMPTS);
        }
        return current;
    }
}
