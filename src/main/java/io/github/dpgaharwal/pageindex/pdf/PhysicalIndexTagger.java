package io.github.dpgaharwal.pageindex.pdf;

import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tags pages with &lt;physical_index_X&gt; markers for unambiguous identification.
 * Every LLM prompt in TOC processing and verification relies on these markers.
 * Mirrors the inline tagging logic from page_index.py.
 */
public class PhysicalIndexTagger {

    private static final Pattern TAG_PATTERN =
            Pattern.compile("<physical_index_(\\d+)>");
    private static final Pattern FIRST_SECTION_PATTERN =
            Pattern.compile("<physical_index_\\d+>.*?<physical_index_\\d+>\\s*", Pattern.DOTALL);

    /**
     * Wraps a single page's text in physical_index tags.
     *
     * @param physicalIndex 0-based index of the page in the document
     * @param pageText      raw text of the page
     */
    public static String tagPage(int physicalIndex, String pageText) {
        return "<physical_index_" + physicalIndex + ">\n"
                + pageText + "\n"
                + "<physical_index_" + physicalIndex + ">\n\n";
    }

    /**
     * Tags a range of pages and joins them into one string block.
     *
     * @param pages      full page list
     * @param startIndex 0-based start index into pages list
     * @param endIndex   0-based end index (inclusive)
     */
    public static String tagPages(List<PageData> pages, int startIndex, int endIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; i <= endIndex && i < pages.size(); i++) {
            sb.append(tagPage(i, pages.get(i).getText()));
        }
        return sb.toString();
    }

    /** Tag all pages from startIndex to end of list. */
    public static String tagPages(List<PageData> pages, int startIndex) {
        return tagPages(pages, startIndex, pages.size() - 1);
    }

    /**
     * Parse a "&lt;physical_index_7&gt;" tag string → 7.
     * Returns empty if the string is not a valid tag.
     */
    public static OptionalInt parseTag(String tag) {
        if (tag == null) return OptionalInt.empty();
        Matcher m = TAG_PATTERN.matcher(tag.trim());
        if (m.find()) {
            return OptionalInt.of(Integer.parseInt(m.group(1)));
        }
        return OptionalInt.empty();
    }

    /**
     * Strip the first &lt;physical_index_X&gt;...&lt;physical_index_X&gt; block from text.
     * Used when the first TOC page is also a content page and must be removed
     * from further processing. Mirrors remove_physical_index_section().
     */
    public static String removeFirstSection(String text) {
        if (text == null) return "";
        return FIRST_SECTION_PATTERN.matcher(text).replaceFirst("").trim();
    }

    /** Extract all physical_index values mentioned in a tagged text blob. */
    public static List<Integer> extractAllIndices(String taggedText) {
        Matcher m = TAG_PATTERN.matcher(taggedText);
        List<Integer> indices = new java.util.ArrayList<>();
        while (m.find()) {
            int val = Integer.parseInt(m.group(1));
            if (!indices.contains(val)) indices.add(val);
        }
        return indices;
    }
}
