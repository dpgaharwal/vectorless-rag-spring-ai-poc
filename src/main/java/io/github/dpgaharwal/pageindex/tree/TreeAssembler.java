package io.github.dpgaharwal.pageindex.tree;

import io.github.dpgaharwal.pageindex.model.PageNode;
import io.github.dpgaharwal.pageindex.model.TocItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Converts a flat TocItem list into a hierarchical PageNode tree.
 * Mirrors list_to_tree(), add_preface_if_needed(), and post_processing() from page_index.py.
 */
@Slf4j
@Component
public class TreeAssembler {

    /**
     * Full post-processing: assign start/end indices and build tree.
     * Mirrors post_processing(structure, end_physical_index).
     */
    public List<PageNode> postProcess(List<TocItem> items, int endPhysicalIndex) {
        if (items == null || items.isEmpty()) return List.of();

        // Sort by physical index
        List<TocItem> sorted = items.stream()
                .filter(i -> i.getPhysicalIndex() != null)
                .sorted(Comparator.comparingInt(TocItem::getPhysicalIndex))
                .toList();

        if (sorted.isEmpty()) return List.of();

        items = addPrefaceIfNeeded(sorted);

        // Assign start/end indices
        List<PageNode> flat = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            TocItem item = items.get(i);
            int startIndex = item.getPhysicalIndex();
            int endIndex;
            if (i + 1 < items.size()) {
                int nextStart = items.get(i + 1).getPhysicalIndex();
                // If the next section starts at the same page AND appear_start is "yes",
                // this section ends just before the next
                endIndex = nextStart - 1;
                if ("yes".equalsIgnoreCase(items.get(i + 1).getAppearStart())) {
                    endIndex = nextStart - 1;
                } else {
                    endIndex = nextStart;
                }
            } else {
                endIndex = endPhysicalIndex;
            }
            endIndex = Math.max(startIndex, endIndex);

            flat.add(PageNode.builder()
                    .title(item.getTitle())
                    .startIndex(startIndex)
                    .endIndex(endIndex)
                    .nodes(new ArrayList<>())
                    .build());
        }

        return listToTree(items, flat);
    }

    /**
     * If the first section starts after page 0, prepend a "Preface" node.
     * Mirrors add_preface_if_needed().
     */
    public List<TocItem> addPrefaceIfNeeded(List<TocItem> items) {
        if (items.isEmpty()) return items;
        int firstIndex = items.get(0).getPhysicalIndex();
        if (firstIndex <= 0) return items;

        List<TocItem> result = new ArrayList<>();
        result.add(TocItem.builder()
                .structure("0").title("Preface").physicalIndex(0).build());
        result.addAll(items);
        return result;
    }

    /**
     * Convert flat TocItem list + flat PageNode list → hierarchical PageNode tree.
     * Uses dot-notation structure field: "1.2" is child of "1".
     * Mirrors list_to_tree().
     */
    private List<PageNode> listToTree(List<TocItem> items, List<PageNode> nodes) {
        // Map structure → node index
        Map<String, Integer> structureToIndex = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getStructure() != null) {
                structureToIndex.put(items.get(i).getStructure(), i);
            }
        }

        List<PageNode> roots = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            TocItem item = items.get(i);
            String structure = item.getStructure();
            String parentStructure = getParentStructure(structure);

            if (parentStructure == null || !structureToIndex.containsKey(parentStructure)) {
                roots.add(nodes.get(i));
            } else {
                int parentIdx = structureToIndex.get(parentStructure);
                PageNode parent = nodes.get(parentIdx);
                if (parent.getNodes() == null) parent.setNodes(new ArrayList<>());
                parent.getNodes().add(nodes.get(i));
                // Update parent end index to cover child
                if (nodes.get(i).getEndIndex() > parent.getEndIndex()) {
                    parent.setEndIndex(nodes.get(i).getEndIndex());
                }
            }
        }

        // Clean up empty node lists
        nodes.forEach(n -> { if (n.getNodes() != null && n.getNodes().isEmpty()) n.setNodes(null); });

        return roots;
    }

    /** "1.2.3" → "1.2", "1.2" → "1", "1" → null */
    private String getParentStructure(String structure) {
        if (structure == null) return null;
        int lastDot = structure.lastIndexOf('.');
        if (lastDot < 0) return null;
        return structure.substring(0, lastDot);
    }
}
