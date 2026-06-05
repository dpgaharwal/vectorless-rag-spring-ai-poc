package io.github.dpgaharwal.pageindex.toc;

import io.github.dpgaharwal.pageindex.config.PageIndexConfig;
import io.github.dpgaharwal.pageindex.model.TocCheckResult;
import io.github.dpgaharwal.pageindex.model.TocItem;
import io.github.dpgaharwal.pageindex.model.TocMode;
import io.github.dpgaharwal.pageindex.pdf.PageData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Orchestrator: decides which TOC mode to run and handles fallback.
 * Mirrors meta_processor() from page_index.py.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TocMetaProcessor {

    private final TocDetector detector;
    private final TocProcessor processor;

    /**
     * Auto-detect mode from pages and run the appropriate processor.
     * This is the primary entry point called by TreeParser.
     */
    public List<TocItem> process(List<PageData> pages, PageIndexConfig config) {
        TocCheckResult tocResult = detector.checkToc(pages, config.getTocCheckPageNum());
        TocMode mode = determineMode(tocResult);
        return process(pages, mode, tocResult.getTocContent(), tocResult.getTocPageList(),
                firstContentPage(tocResult.getTocPageList()), config);
    }

    /**
     * Run processing with an explicitly specified mode (used by NodeSplitter for sub-trees).
     */
    public List<TocItem> process(List<PageData> pages,
                                  TocMode mode,
                                  String tocContent,
                                  List<Integer> tocPageList,
                                  int startIndex,
                                  PageIndexConfig config) {
        log.info("TocMetaProcessor: mode={}, startIndex={}", mode, startIndex);
        try {
            return switch (mode) {
                case WITH_PAGE_NUMBERS ->
                        processor.processWithPageNumbers(tocContent, tocPageList, pages,
                                config.getTocCheckPageNum(), config);
                case NO_PAGE_NUMBERS ->
                        processor.processNoPageNumbers(tocContent, tocPageList, pages,
                                startIndex, config);
                case NO_TOC ->
                        processor.processNoToc(pages, startIndex, config);
            };
        } catch (Exception e) {
            log.error("TOC processing failed for mode {}, falling back to NO_TOC: {}", mode, e.getMessage());
            return processor.processNoToc(pages, startIndex, config);
        }
    }

    private TocMode determineMode(TocCheckResult result) {
        if (result.getTocPageList().isEmpty()) return TocMode.NO_TOC;
        return result.isPageIndexGivenInToc() ? TocMode.WITH_PAGE_NUMBERS : TocMode.NO_PAGE_NUMBERS;
    }

    private int firstContentPage(List<Integer> tocPageList) {
        if (tocPageList == null || tocPageList.isEmpty()) return 0;
        return tocPageList.get(tocPageList.size() - 1) + 1;
    }
}
