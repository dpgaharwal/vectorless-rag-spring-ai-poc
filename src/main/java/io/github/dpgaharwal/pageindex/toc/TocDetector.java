package io.github.dpgaharwal.pageindex.toc;

import io.github.dpgaharwal.pageindex.model.TocCheckResult;
import io.github.dpgaharwal.pageindex.pdf.PageData;
import io.github.dpgaharwal.pageindex.pdf.PhysicalIndexTagger;
import io.github.dpgaharwal.pageindex.util.JsonExtractor;
import io.github.dpgaharwal.pageindex.util.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects whether a PDF has a Table of Contents and extracts its raw text.
 * Mirrors toc_detector_single_page(), find_toc_pages(), check_toc() from page_index.py.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TocDetector {

    private final LlmGateway llm;
    private final JsonExtractor json;

    private static final String DETECT_PROMPT = """
            You are analyzing a page from a document. Determine if this page is a Table of Contents (TOC) page.

            Page content:
            %s

            Respond ONLY with a JSON object:
            {"thinking": "<brief reasoning>", "toc_detected": "yes" or "no"}
            """;

    private static final String PAGE_INDEX_PROMPT = """
            Look at this Table of Contents text. Does it contain page numbers next to the chapter/section titles?

            TOC content:
            %s

            Respond ONLY with a JSON object:
            {"thinking": "<brief reasoning>", "page_index_given_in_toc": "yes" or "no"}
            """;

    /**
     * Detect if a single page is a TOC page. Returns "yes" or "no".
     * Mirrors toc_detector_single_page().
     */
    public String detectSinglePage(String pageContent) {
        String prompt = String.format(DETECT_PROMPT, pageContent);
        String response = llm.call(prompt).getContent();
        var node = json.extract(response);
        if (node.has("toc_detected")) return node.get("toc_detected").asText("no");
        return "no";
    }

    /**
     * Scan pages to find TOC pages. Returns list of 0-based page indices.
     * Mirrors find_toc_pages().
     */
    public List<Integer> findTocPages(List<PageData> pages, int startPageIndex, int tocCheckPageNum) {
        List<Integer> tocPageIndices = new ArrayList<>();
        int limit = Math.min(startPageIndex + tocCheckPageNum, pages.size());

        for (int i = startPageIndex; i < limit; i++) {
            String text = pages.get(i).getText();
            if (text == null || text.isBlank()) continue;
            String result = detectSinglePage(text);
            if ("yes".equalsIgnoreCase(result.trim())) {
                tocPageIndices.add(i);
                log.debug("TOC page detected at physical index {}", i);
            }
        }
        return tocPageIndices;
    }

    /**
     * Full TOC detection: finds TOC pages, concatenates their content, detects if page numbers are present.
     * Mirrors check_toc().
     */
    public TocCheckResult checkToc(List<PageData> pages, int tocCheckPageNum) {
        List<Integer> tocPageList = findTocPages(pages, 0, tocCheckPageNum);

        if (tocPageList.isEmpty()) {
            log.info("No TOC detected in first {} pages", tocCheckPageNum);
            return new TocCheckResult(null, List.of(), false);
        }

        // Concatenate TOC page texts
        StringBuilder tocContent = new StringBuilder();
        for (int idx : tocPageList) {
            tocContent.append(pages.get(idx).getText()).append("\n");
        }
        String tocText = tocContent.toString().trim();

        // Detect if page numbers are present in TOC
        String prompt = String.format(PAGE_INDEX_PROMPT, tocText);
        String response = llm.call(prompt).getContent();
        var node = json.extract(response);
        boolean hasPageNumbers = false;
        if (node.has("page_index_given_in_toc")) {
            hasPageNumbers = "yes".equalsIgnoreCase(node.get("page_index_given_in_toc").asText());
        }

        log.info("TOC detected on pages {}, has page numbers: {}", tocPageList, hasPageNumbers);
        return new TocCheckResult(tocText, tocPageList, hasPageNumbers);
    }
}
