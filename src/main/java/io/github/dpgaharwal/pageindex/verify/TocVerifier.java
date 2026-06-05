package io.github.dpgaharwal.pageindex.verify;

import io.github.dpgaharwal.pageindex.model.TocItem;
import io.github.dpgaharwal.pageindex.pdf.PageData;
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
 * Verifies that section titles actually appear on their assigned pages.
 * Mirrors check_title_appearance(), check_title_appearance_in_start(),
 * verify_toc(), and validate_and_truncate_physical_indices() from page_index.py.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TocVerifier {

    private final LlmGateway llm;
    private final JsonExtractor json;

    private static final String CHECK_APPEARANCE_PROMPT = """
            Does the section titled "%s" appear anywhere on this page?

            Page content:
            %s

            Respond ONLY with JSON: {"thinking": "<brief reasoning>", "answer": "yes" or "no"}
            """;

    private static final String CHECK_START_PROMPT = """
            Does the section titled "%s" START at the beginning of this page?
            (i.e., the title appears near the top of the page, not mid-page)

            Page content:
            %s

            Respond ONLY with JSON: {"thinking": "<brief reasoning>", "start_begin": "yes" or "no"}
            """;

    /**
     * Check if a title appears on its assigned page (async).
     * Mirrors check_title_appearance().
     */
    public CompletableFuture<TitleCheckResult> checkTitleAppearance(
            TocItem item, List<PageData> pages, int startIndex, int listIndex) {
        return llm.callAsync(buildAppearancePrompt(item, pages, startIndex))
                .thenApply(response -> {
                    var node = json.extract(response);
                    String answer = node.has("answer") ? node.get("answer").asText("no") : "no";
                    return new TitleCheckResult(listIndex, answer, item.getTitle(), item.getPhysicalIndex());
                });
    }

    /**
     * Check if a title appears at the START of its page (async).
     * Mirrors check_title_appearance_in_start().
     */
    public CompletableFuture<String> checkTitleAppearanceInStart(String title, String pageText) {
        String prompt = String.format(CHECK_START_PROMPT, title, pageText);
        return llm.callAsync(prompt).thenApply(response -> {
            var node = json.extract(response);
            return node.has("start_begin") ? node.get("start_begin").asText("no") : "no";
        });
    }

    /**
     * Concurrently check all items' title-start appearance and update appearStart field.
     * Mirrors check_title_appearance_in_start_concurrent().
     */
    public List<TocItem> checkAllTitlesInStartConcurrently(List<TocItem> structure, List<PageData> pages) {
        List<Supplier<CompletableFuture<String>>> tasks = new ArrayList<>();
        for (TocItem item : structure) {
            if (item.getPhysicalIndex() != null && item.getPhysicalIndex() < pages.size()) {
                String pageText = pages.get(item.getPhysicalIndex()).getText();
                tasks.add(() -> checkTitleAppearanceInStart(item.getTitle(), pageText));
            } else {
                tasks.add(() -> CompletableFuture.completedFuture("no"));
            }
        }
        List<String> results = llm.gatherAsync(tasks);

        List<TocItem> updated = new ArrayList<>();
        for (int i = 0; i < structure.size(); i++) {
            TocItem item = structure.get(i);
            String startBegin = (results.get(i) != null) ? results.get(i) : "no";
            updated.add(TocItem.builder()
                    .structure(item.getStructure()).title(item.getTitle())
                    .page(item.getPage()).physicalIndex(item.getPhysicalIndex())
                    .appearStart(startBegin).build());
        }
        return updated;
    }

    /**
     * Sample N items and verify they appear on assigned pages.
     * Mirrors verify_toc().
     */
    public VerifyResult verify(List<PageData> pages, List<TocItem> tocItems,
                                int startIndex, int sampleSize) {
        if (tocItems.isEmpty()) return new VerifyResult(1.0, List.of());

        // Sample up to sampleSize items that have physical indices
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < tocItems.size(); i++) {
            if (tocItems.get(i).getPhysicalIndex() != null) indices.add(i);
        }
        Collections.shuffle(indices, new Random(42));
        List<Integer> sample = indices.subList(0, Math.min(sampleSize, indices.size()));

        // Concurrent checks
        List<Supplier<CompletableFuture<TitleCheckResult>>> tasks = sample.stream()
                .map(idx -> (Supplier<CompletableFuture<TitleCheckResult>>)
                        () -> checkTitleAppearance(tocItems.get(idx), pages, startIndex, idx))
                .collect(Collectors.toList());

        List<TitleCheckResult> results = llm.gatherAsync(tasks);
        results = results.stream().filter(Objects::nonNull).collect(Collectors.toList());

        List<TitleCheckResult> incorrect = results.stream()
                .filter(r -> !r.isCorrect()).collect(Collectors.toList());

        double accuracy = results.isEmpty() ? 1.0 : (double)(results.size() - incorrect.size()) / results.size();
        log.info("TOC verification: {}/{} correct ({:.0f}%)", results.size() - incorrect.size(),
                results.size(), accuracy * 100);
        return new VerifyResult(accuracy, incorrect);
    }

    /**
     * Clamp or null-out physical indices exceeding document length.
     * Mirrors validate_and_truncate_physical_indices().
     */
    public List<TocItem> validateAndTruncate(List<TocItem> items, int pageListLength, int startIndex) {
        return items.stream().map(item -> {
            if (item.getPhysicalIndex() == null) return item;
            if (item.getPhysicalIndex() < startIndex || item.getPhysicalIndex() >= pageListLength) {
                return TocItem.builder()
                        .structure(item.getStructure()).title(item.getTitle())
                        .page(item.getPage()).physicalIndex(null).appearStart(item.getAppearStart())
                        .build();
            }
            return item;
        }).collect(Collectors.toList());
    }

    private String buildAppearancePrompt(TocItem item, List<PageData> pages, int startIndex) {
        String pageText = "";
        if (item.getPhysicalIndex() != null) {
            int idx = item.getPhysicalIndex();
            if (idx >= 0 && idx < pages.size()) {
                pageText = pages.get(idx).getText();
            }
        }
        return String.format(CHECK_APPEARANCE_PROMPT, item.getTitle(), pageText);
    }
}
