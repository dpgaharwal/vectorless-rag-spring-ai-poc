package io.github.dpgaharwal.pageindex.markdown;

import io.github.dpgaharwal.pageindex.util.TokenCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses a Markdown file into a flat list of MarkdownNodes representing each heading.
 * Mirrors extract_nodes_from_markdown() and extract_node_text_content() from page_index_md.py.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarkdownParser {

    private final TokenCounter tokenCounter;

    private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern CODE_FENCE = Pattern.compile("^```");

    /**
     * Parse all headings from a Markdown file into MarkdownNodes with text content.
     * Respects code fences — headers inside code blocks are ignored.
     * Mirrors extract_nodes_from_markdown() + extract_node_text_content().
     */
    public List<MarkdownNode> parse(Path mdPath) throws IOException {
        List<String> lines = Files.readAllLines(mdPath);
        return parseLines(lines);
    }

    public List<MarkdownNode> parseLines(List<String> lines) {
        List<MarkdownNode> headerNodes = new ArrayList<>();
        boolean inCodeBlock = false;

        // First pass: find all headers and their line numbers
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (CODE_FENCE.matcher(line.trim()).find()) {
                inCodeBlock = !inCodeBlock;
                continue;
            }
            if (inCodeBlock) continue;

            var headerMatcher = HEADER_PATTERN.matcher(line);
            if (headerMatcher.matches()) {
                int level = headerMatcher.group(1).length();
                String title = headerMatcher.group(2).trim();
                headerNodes.add(MarkdownNode.builder()
                        .title(title).lineNum(i).level(level).build());
            }
        }

        // Second pass: assign text content from header line to next header line
        for (int i = 0; i < headerNodes.size(); i++) {
            int startLine = headerNodes.get(i).getLineNum() + 1;
            int endLine = (i + 1 < headerNodes.size())
                    ? headerNodes.get(i + 1).getLineNum()
                    : lines.size();

            StringBuilder content = new StringBuilder();
            for (int l = startLine; l < endLine && l < lines.size(); l++) {
                content.append(lines.get(l)).append("\n");
            }
            String text = content.toString().trim();
            headerNodes.get(i).setText(text);
            headerNodes.get(i).setTextTokenCount(tokenCounter.count(text));
        }

        log.debug("Parsed {} markdown headers", headerNodes.size());
        return headerNodes;
    }

    /**
     * Apply thinning: merge nodes below minTokenThreshold with their content.
     * Mirrors tree_thinning_for_index().
     */
    public List<MarkdownNode> thin(List<MarkdownNode> nodes, int minTokenThreshold) {
        if (nodes.isEmpty()) return nodes;
        List<MarkdownNode> result = new ArrayList<>();

        for (MarkdownNode node : nodes) {
            if (node.getTextTokenCount() < minTokenThreshold && !result.isEmpty()) {
                // Merge into previous node
                MarkdownNode prev = result.get(result.size() - 1);
                String merged = prev.getText() + "\n\n## " + node.getTitle() + "\n" + node.getText();
                prev.setText(merged);
                prev.setTextTokenCount(tokenCounter.count(merged));
            } else {
                result.add(node);
            }
        }
        return result;
    }
}
