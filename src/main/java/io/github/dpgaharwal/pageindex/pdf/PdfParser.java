package io.github.dpgaharwal.pageindex.pdf;

import io.github.dpgaharwal.pageindex.util.TokenCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts per-page text from PDF files.
 * Supports two backends: Spring AI PagePdfDocumentReader (default) and Apache PDFBox.
 * Mirrors get_page_tokens() from utils.py.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfParser {

    private final TokenCounter tokenCounter;

    public enum Backend { SPRING_AI, PDFBOX }

    /** Parse with the default Spring AI backend. */
    public List<PageData> parse(Path pdfPath) {
        return parse(pdfPath, Backend.SPRING_AI);
    }

    public List<PageData> parse(Path pdfPath, Backend backend) {
        return switch (backend) {
            case SPRING_AI -> parseWithSpringAi(pdfPath);
            case PDFBOX -> parseWithPdfBox(pdfPath);
        };
    }

    private List<PageData> parseWithSpringAi(Path pdfPath) {
        try {
            PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                    .withPagesPerDocument(1)
                    .build();
            PagePdfDocumentReader reader = new PagePdfDocumentReader(
                    new FileSystemResource(pdfPath.toFile()), config);
            List<Document> docs = reader.read();
            List<PageData> result = new ArrayList<>();
            for (int i = 0; i < docs.size(); i++) {
                String text = docs.get(i).getText();
                if (text == null) text = "";
                result.add(new PageData(i + 1, text, tokenCounter.count(text)));
            }
            log.debug("Spring AI parsed {} pages from {}", result.size(), pdfPath.getFileName());
            return result;
        } catch (Exception e) {
            log.warn("Spring AI PDF parse failed for {}, falling back to PDFBox: {}", pdfPath, e.getMessage());
            return parseWithPdfBox(pdfPath);
        }
    }

    private List<PageData> parseWithPdfBox(Path pdfPath) {
        List<PageData> result = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pageCount = doc.getNumberOfPages();
            for (int p = 1; p <= pageCount; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(doc);
                if (text == null) text = "";
                result.add(new PageData(p, text.trim(), tokenCounter.count(text)));
            }
            log.debug("PDFBox parsed {} pages from {}", result.size(), pdfPath.getFileName());
        } catch (IOException e) {
            log.error("PDFBox parse failed for {}: {}", pdfPath, e.getMessage());
        }
        return result;
    }
}
