package io.github.dpgaharwal.pageindex.toc;

import io.github.dpgaharwal.pageindex.model.TocItem;
import io.github.dpgaharwal.pageindex.toc.model.PagePair;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Calculates the page offset between TOC-reported page numbers and physical page indices.
 * Mirrors calculate_page_offset() and add_page_offset_to_toc_json() from page_index.py.
 */
@Slf4j
@Component
public class TocOffsetCalculator {

    /**
     * Build matched pairs from two parallel lists (with-page-numbers and with-physical-index).
     * Mirrors extract_matching_page_pairs().
     */
    public List<PagePair> extractMatchingPairs(List<TocItem> withPageNums,
                                                List<TocItem> withPhysicalIndex,
                                                int startPageIndex) {
        List<PagePair> pairs = new ArrayList<>();
        int limit = Math.min(withPageNums.size(), withPhysicalIndex.size());
        for (int i = 0; i < limit; i++) {
            TocItem num = withPageNums.get(i);
            TocItem phys = withPhysicalIndex.get(i);
            if (num.getPage() != null && phys.getPhysicalIndex() != null) {
                pairs.add(new PagePair(num.getPage(), phys.getPhysicalIndex() + startPageIndex));
            }
        }
        return pairs;
    }

    /**
     * Find the modal (most common) difference between physical index and TOC page.
     * Mirrors calculate_page_offset().
     */
    public OptionalInt calculateOffset(List<PagePair> pairs) {
        if (pairs.isEmpty()) return OptionalInt.empty();
        Map<Integer, Long> freq = pairs.stream()
                .collect(Collectors.groupingBy(PagePair::difference, Collectors.counting()));
        return OptionalInt.of(freq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0));
    }

    /**
     * Apply offset: physicalIndex = tocPage + offset.
     * Mirrors add_page_offset_to_toc_json().
     */
    public List<TocItem> applyOffset(List<TocItem> items, int offset, int maxPageIndex) {
        List<TocItem> result = new ArrayList<>();
        for (TocItem item : items) {
            TocItem copy = copyItem(item);
            if (item.getPage() != null) {
                int computed = item.getPage() + offset;
                // Clamp to valid range
                computed = Math.max(0, Math.min(computed, maxPageIndex));
                copy.setPhysicalIndex(computed);
            }
            result.add(copy);
        }
        return result;
    }

    private TocItem copyItem(TocItem src) {
        return TocItem.builder()
                .structure(src.getStructure())
                .title(src.getTitle())
                .page(src.getPage())
                .physicalIndex(src.getPhysicalIndex())
                .appearStart(src.getAppearStart())
                .build();
    }
}
