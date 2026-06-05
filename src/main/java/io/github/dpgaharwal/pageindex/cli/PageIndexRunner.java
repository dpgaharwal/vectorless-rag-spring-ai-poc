package io.github.dpgaharwal.pageindex.cli;

import io.github.dpgaharwal.pageindex.PageIndexClient;
import io.github.dpgaharwal.pageindex.config.PageIndexConfig;
import io.github.dpgaharwal.pageindex.model.IndexMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CLI entry point — activated when --pageindex flag is present.
 * Mirrors run_pageindex.py argument parser.
 *
 * Usage:
 *   java -jar pageindex-spring-ai.jar --pageindex --pdf-path=/abs/path/to/file.pdf
 *   java -jar pageindex-spring-ai.jar --pageindex --md-path=/abs/path/to/doc.md --thinning=true
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PageIndexRunner implements ApplicationRunner {

    private final PageIndexClient pageIndexClient;

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("pageindex")) return;

        Path filePath = null;
        IndexMode mode = IndexMode.AUTO;

        if (args.containsOption("pdf-path")) {
            filePath = Paths.get(args.getOptionValues("pdf-path").get(0));
            mode = IndexMode.PDF;
        } else if (args.containsOption("md-path")) {
            filePath = Paths.get(args.getOptionValues("md-path").get(0));
            mode = IndexMode.MARKDOWN;
        }

        if (filePath == null) {
            log.error("--pageindex requires --pdf-path or --md-path");
            return;
        }

        if (!Files.exists(filePath)) {
            log.error("File not found: {}", filePath);
            return;
        }

        // Build optional overrides from CLI args
        PageIndexConfig.PageIndexConfigBuilder overridesBuilder = PageIndexConfig.builder();
        if (args.containsOption("model"))
            overridesBuilder.model(args.getOptionValues("model").get(0));
        if (args.containsOption("add-node-summary"))
            overridesBuilder.addNodeSummary(Boolean.parseBoolean(args.getOptionValues("add-node-summary").get(0)));
        if (args.containsOption("add-doc-description"))
            overridesBuilder.addDocDescription(Boolean.parseBoolean(args.getOptionValues("add-doc-description").get(0)));
        if (args.containsOption("add-node-text"))
            overridesBuilder.addNodeText(Boolean.parseBoolean(args.getOptionValues("add-node-text").get(0)));
        if (args.containsOption("max-pages-per-node"))
            overridesBuilder.maxPageNumEachNode(Integer.parseInt(args.getOptionValues("max-pages-per-node").get(0)));

        PageIndexConfig overrides = overridesBuilder.build();

        log.info("PageIndex CLI: indexing {}", filePath);
        long start = System.currentTimeMillis();
        String docId = pageIndexClient.index(filePath, mode, overrides);
        long elapsed = System.currentTimeMillis() - start;

        // Write result to ./results/<name>_structure.json
        String structure = pageIndexClient.getDocumentStructure(docId);
        try {
            Path resultsDir = Paths.get("results");
            Files.createDirectories(resultsDir);
            String outName = filePath.getFileName().toString().replaceAll("\\.[^.]+$", "") + "_structure.json";
            Path outPath = resultsDir.resolve(outName);
            Files.writeString(outPath, structure);
            log.info("Structure written to: {}", outPath);
        } catch (Exception e) {
            log.error("Could not write result file: {}", e.getMessage());
        }

        log.info("Done in {}ms. Document ID: {}", elapsed, docId);
    }
}
