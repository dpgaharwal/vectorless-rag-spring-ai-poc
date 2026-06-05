package io.github.dpgaharwal.pageindex.tree;

import io.github.dpgaharwal.pageindex.config.PageIndexConfig;
import io.github.dpgaharwal.pageindex.model.PageNode;
import io.github.dpgaharwal.pageindex.model.TocItem;
import io.github.dpgaharwal.pageindex.pdf.PageData;
import io.github.dpgaharwal.pageindex.toc.TocMetaProcessor;
import io.github.dpgaharwal.pageindex.verify.TocFixer;
import io.github.dpgaharwal.pageindex.verify.TocVerifier;
import io.github.dpgaharwal.pageindex.verify.VerifyResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Full orchestration: detect TOC → process → verify → build tree → split large nodes.
 * Mirrors tree_parser() from page_index.py.
 * This is the main entry point for the indexing pipeline.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TreeParser {

    private final TocMetaProcessor metaProcessor;
    private final TocVerifier verifier;
    private final TocFixer fixer;
    private final TreeAssembler assembler;
    private final NodeSplitter nodeSplitter;

    private static final int VERIFY_SAMPLE_SIZE = 10;
    private static final double ACCURACY_THRESHOLD = 0.8;

    /**
     * Parse a PDF's page list into a verified hierarchical tree.
     * Mirrors tree_parser().
     */
    public List<PageNode> parse(List<PageData> pages, PageIndexConfig config) {
        log.info("TreeParser: starting parse of {} pages", pages.size());

        // Step 1: TOC detection and processing
        List<TocItem> tocItems = metaProcessor.process(pages, config);
        log.info("TOC processing produced {} items", tocItems.size());

        if (tocItems.isEmpty()) {
            log.warn("No structure produced, returning single root node");
            return List.of(PageNode.builder()
                    .title("Document").startIndex(0).endIndex(pages.size() - 1).build());
        }

        // Step 2: Validate and clamp indices
        tocItems = verifier.validateAndTruncate(tocItems, pages.size(), 0);

        // Step 3: Verify accuracy of assignments
        VerifyResult verifyResult = verifier.verify(pages, tocItems, 0, VERIFY_SAMPLE_SIZE);
        log.info("Verification accuracy: {:.0f}%", verifyResult.getAccuracy() * 100);

        // Step 4: Fix incorrect assignments
        if (verifyResult.getAccuracy() < ACCURACY_THRESHOLD && !verifyResult.getIncorrectResults().isEmpty()) {
            log.info("Fixing {} incorrect assignments", verifyResult.getIncorrectResults().size());
            tocItems = fixer.fixWithRetries(tocItems, pages, verifyResult.getIncorrectResults(), 0);
        }

        // Step 5: Check appear_start for all items
        tocItems = verifier.checkAllTitlesInStartConcurrently(tocItems, pages);

        // Step 6: Build tree from flat list
        List<PageNode> tree = assembler.postProcess(tocItems, pages.size() - 1);
        log.info("Tree assembled: {} root nodes", tree.size());

        // Step 7: Recursively split large nodes
        tree = nodeSplitter.splitAllLargeNodes(tree, pages, config);
        log.info("TreeParser: done. Final tree has {} root nodes", tree.size());

        return tree;
    }
}
