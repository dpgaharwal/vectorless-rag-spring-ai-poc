package io.github.dpgaharwal.pageindex;

import io.github.dpgaharwal.pageindex.pdf.PhysicalIndexTagger;
import io.github.dpgaharwal.pageindex.retrieve.DocumentRetrieval;
import io.github.dpgaharwal.pageindex.toc.TocOffsetCalculator;
import io.github.dpgaharwal.pageindex.toc.model.PagePair;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for pure (non-LLM) utility classes.
 * Integration tests requiring a real ChatModel are in PageIndexIntegrationTest.
 */
class PageIndexClientTest {

    // ── PhysicalIndexTagger ───────────────────────────────────────────────────

    @Test
    void tagPage_producesExpectedFormat() {
        String tagged = PhysicalIndexTagger.tagPage(3, "Hello page");
        assertThat(tagged).contains("<physical_index_3>");
        assertThat(tagged).contains("Hello page");
    }

    @Test
    void parseTag_extractsIndex() {
        OptionalInt idx = PhysicalIndexTagger.parseTag("<physical_index_7>");
        assertThat(idx).hasValue(7);
    }

    @Test
    void parseTag_returnsEmptyForInvalidInput() {
        assertThat(PhysicalIndexTagger.parseTag("not a tag")).isEmpty();
        assertThat(PhysicalIndexTagger.parseTag(null)).isEmpty();
    }

    @Test
    void removeFirstSection_stripsFirstBlock() {
        String text = "<physical_index_0>\nsome content\n<physical_index_0>\n\nrest of doc";
        String result = PhysicalIndexTagger.removeFirstSection(text);
        assertThat(result).doesNotContain("<physical_index_0>");
        assertThat(result).contains("rest of doc");
    }

    // ── TocOffsetCalculator ───────────────────────────────────────────────────

    @Test
    void calculateOffset_returnsModalDifference() {
        TocOffsetCalculator calc = new TocOffsetCalculator();
        List<PagePair> pairs = List.of(
                new PagePair(1, 3),
                new PagePair(5, 7),
                new PagePair(10, 12),
                new PagePair(20, 21)   // outlier
        );
        OptionalInt offset = calc.calculateOffset(pairs);
        assertThat(offset).hasValue(2);
    }

    @Test
    void calculateOffset_emptyPairs_returnsEmpty() {
        TocOffsetCalculator calc = new TocOffsetCalculator();
        assertThat(calc.calculateOffset(List.of())).isEmpty();
    }

    // ── DocumentRetrieval.parsePages ─────────────────────────────────────────

    @Test
    void parsePages_range() {
        // DocumentRetrieval is a Spring component but parsePages is pure logic
        // We test it via direct instantiation with nulls for unused deps
        var retrieval = new DocumentRetrieval(null, null);
        List<Integer> pages = retrieval.parsePages("5-7");
        assertThat(pages).containsExactly(5, 6, 7);
    }

    @Test
    void parsePages_list() {
        var retrieval = new DocumentRetrieval(null, null);
        List<Integer> pages = retrieval.parsePages("3,8,1");
        assertThat(pages).containsExactly(1, 3, 8);
    }

    @Test
    void parsePages_single() {
        var retrieval = new DocumentRetrieval(null, null);
        List<Integer> pages = retrieval.parsePages("12");
        assertThat(pages).containsExactly(12);
    }

    @Test
    void parsePages_nullReturnsEmpty() {
        var retrieval = new DocumentRetrieval(null, null);
        assertThat(retrieval.parsePages(null)).isEmpty();
    }
}
