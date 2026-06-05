package io.github.dpgaharwal.pageindex.toc;

import io.github.dpgaharwal.pageindex.config.PageIndexConfig;
import io.github.dpgaharwal.pageindex.model.TocItem;
import io.github.dpgaharwal.pageindex.pdf.PageData;
import io.github.dpgaharwal.pageindex.pdf.PageGrouper;
import io.github.dpgaharwal.pageindex.pdf.PhysicalIndexTagger;
import io.github.dpgaharwal.pageindex.toc.model.PagePair;
import io.github.dpgaharwal.pageindex.util.LlmGateway;
import io.github.dpgaharwal.pageindex.util.JsonExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Collectors;

/**
 * Implements all three TOC processing modes.
 * Mirrors process_toc_with_page_numbers(), process_toc_no_page_numbers(),
 * process_no_toc(), and process_none_page_numbers() from page_index.py.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TocProcessor {

    private final TocTransformer transformer;
    private final TocIndexExtractor indexExtractor;
    private final TocOffsetCalculator offsetCalculator;
    private final TocGenerator generator;
    private final PageGrouper pageGrouper;

    /**
     * Mode 1: PDF has TOC with explicit page numbers.
     * Extract structure → extract physical indices from a parallel run → compute offset → apply.
     * Mirrors process_toc_with_page_numbers().
     */
    public List<TocItem> processWithPageNumbers(String tocContent,
                                                 List<Integer> tocPageList,
                                                 List<PageData> pages,
                                                 int tocCheckPageNum,
                                                 PageIndexConfig config) {
        log.info("Processing TOC with page numbers");

        // Step 1: Transform TOC text → structured items (with page numbers)
        List<TocItem> withPageNums = transformer.transform(tocContent);
        if (withPageNums.isEmpty()) {
            log.warn("TOC transformation produced no items, falling back to NO_TOC mode");
            return processNoToc(pages, firstContentPage(tocPageList), config);
        }

        // Step 2: Find physical indices by scanning pages after TOC
        int contentStart = firstContentPage(tocPageList);
        String taggedPages = PhysicalIndexTagger.tagPages(pages, contentStart,
                Math.min(contentStart + tocCheckPageNum - 1, pages.size() - 1));
        List<TocItem> withPhysical = indexExtractor.extractPhysicalIndices(withPageNums, taggedPages);

        // Step 3: Calculate page offset (modal difference)
        List<PagePair> pairs = offsetCalculator.extractMatchingPairs(withPageNums, withPhysical, contentStart);
        OptionalInt offset = offsetCalculator.calculateOffset(pairs);

        if (offset.isPresent()) {
            log.info("Page offset calculated: {}", offset.getAsInt());
            return offsetCalculator.applyOffset(withPageNums, offset.getAsInt(), pages.size() - 1);
        }

        // Fallback: use the physical indices directly
        return withPhysical;
    }

    /**
     * Mode 2: PDF has TOC but without explicit page numbers.
     * Scan pages sequentially and ask LLM where each section starts.
     * Mirrors process_toc_no_page_numbers().
     */
    public List<TocItem> processNoPageNumbers(String tocContent,
                                               List<Integer> tocPageList,
                                               List<PageData> pages,
                                               int startIndex,
                                               PageIndexConfig config) {
        log.info("Processing TOC without page numbers");

        // Extract clean TOC structure
        List<TocItem> tocStructure = transformer.transform(tocContent);
        if (tocStructure.isEmpty()) {
            return processNoToc(pages, startIndex, config);
        }

        // Scan pages in groups, adding physical indices
        List<String> pageGroups = pageGrouper.groupIntoChunks(pages, startIndex,
                config.getMaxTokenNumEachNode());

        List<TocItem> currentStructure = tocStructure;
        for (String group : pageGroups) {
            currentStructure = indexExtractor.addPageNumbers(group, currentStructure);
            // Stop if all items have physical indices
            boolean allAssigned = currentStructure.stream()
                    .allMatch(item -> item.getPhysicalIndex() != null);
            if (allAssigned) break;
        }

        return fillMissingPageNumbers(currentStructure, pages, startIndex);
    }

    /**
     * Mode 3: No TOC at all — generate structure directly from page content.
     * Mirrors process_no_toc().
     */
    public List<TocItem> processNoToc(List<PageData> pages, int startIndex, PageIndexConfig config) {
        log.info("Generating structure without TOC (startIndex={})", startIndex);

        List<String> pageGroups = pageGrouper.groupIntoChunks(pages, startIndex,
                config.getMaxTokenNumEachNode());

        if (pageGroups.isEmpty()) {
            return List.of(TocItem.builder()
                    .structure("1").title("Document").physicalIndex(startIndex).build());
        }

        List<TocItem> allItems = new ArrayList<>();
        List<TocItem> currentStructure = new ArrayList<>();

        for (int g = 0; g < pageGroups.size(); g++) {
            String group = pageGroups.get(g);
            List<TocItem> newItems;
            if (g == 0) {
                newItems = generator.generateInit(group);
                allItems.addAll(newItems);
                currentStructure = newItems;
            } else {
                newItems = generator.generateContinue(currentStructure, group);
                allItems.addAll(newItems);
                currentStructure = newItems;
            }
        }

        log.info("Generated {} structure items without TOC", allItems.size());
        return allItems;
    }

    /**
     * Fill null physical_index values using page range inference.
     * Mirrors process_none_page_numbers().
     */
    public List<TocItem> fillMissingPageNumbers(List<TocItem> items,
                                                  List<PageData> pages,
                                                  int startIndex) {
        List<TocItem> result = new ArrayList<>(items);
        int lastKnown = startIndex;

        for (int i = 0; i < result.size(); i++) {
            TocItem item = result.get(i);
            if (item.getPhysicalIndex() != null) {
                lastKnown = item.getPhysicalIndex();
                continue;
            }

            // Find next known index
            int nextKnown = pages.size() - 1;
            for (int j = i + 1; j < result.size(); j++) {
                if (result.get(j).getPhysicalIndex() != null) {
                    nextKnown = result.get(j).getPhysicalIndex();
                    break;
                }
            }

            // Interpolate: distribute evenly between lastKnown and nextKnown
            int unknownCount = 0;
            for (int j = i; j < result.size(); j++) {
                if (result.get(j).getPhysicalIndex() == null) unknownCount++;
                else break;
            }
            int step = (unknownCount > 0) ? (nextKnown - lastKnown) / (unknownCount + 1) : 1;
            int assigned = lastKnown + step;
            item.setPhysicalIndex(Math.min(assigned, pages.size() - 1));
            lastKnown = assigned;
        }

        return result;
    }

    private int firstContentPage(List<Integer> tocPageList) {
        if (tocPageList.isEmpty()) return 0;
        return tocPageList.get(tocPageList.size() - 1) + 1;
    }
}
