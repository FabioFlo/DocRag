package com.ex.docrag.service;

import com.ex.docrag.config.DocRagProperties;
import com.ex.docrag.model.DocumentInfo;
import com.ex.docrag.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Handles reading, chunking, embedding, and storing documents into Qdrant.
 *
 * Flow:
 *  1. On startup (@PostConstruct), scan the documents root folder recursively.
 *  2. For each supported file not yet ingested, call ingestFile().
 *  3. ingestFile() reads text, splits into chunks, adds metadata, and stores in VectorStore.
 *  4. A simple in-memory set tracks ingested paths to avoid duplicates across restarts.
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final VectorStore vectorStore;
    private final DocRagProperties props;

    /**
     * Tracks absolute paths of files that have already been ingested in this session.
     * ConcurrentHashMap.newKeySet() is thread-safe (needed by FolderWatcherService).
     */
    private final Set<String> ingestedPaths = ConcurrentHashMap.newKeySet();

    /** In-memory catalogue of all ingested document metadata, used by the UI. */
    private final List<DocumentInfo> documentCatalogue = Collections.synchronizedList(new ArrayList<>());

    public DocumentIngestionService(VectorStore vectorStore, DocRagProperties props) {
        this.vectorStore = vectorStore;
        this.props = props;
    }

    /**
     * Called automatically after the Spring context is fully initialised.
     * Scans the documents folder and ingests everything found.
     */
    @PostConstruct
    public void ingestAllOnStartup() {
        log.info("=== DocRAG Startup Ingestion BEGIN ===");
        log.info("Documents root: {}", props.getDocumentsPath());

        Path root = Paths.get(props.getDocumentsPath());
        if (!Files.exists(root)) {
            log.warn("Documents path '{}' does not exist. Creating it now.", root);
            try {
                Files.createDirectories(root);
                log.info("Created documents directory: {}", root);
            } catch (IOException e) {
                log.error("Failed to create documents directory: {}", e.getMessage(), e);
            }
            return; // nothing to ingest yet
        }

        // Walk all files recursively
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> files = paths
                    .filter(Files::isRegularFile)
                    .toList();

            log.info("Found {} files in documents tree. Processing supported types...", files.size());

            for (Path p : files) {
                ingestFile(p.toFile());
            }

        } catch (IOException e) {
            log.error("Error scanning documents directory '{}': {}", root, e.getMessage(), e);
        }

        log.info("=== DocRAG Startup Ingestion END — {} documents indexed ===", documentCatalogue.size());
    }

    /**
     * Ingests a single file: reads text → chunks → embeds → stores in Qdrant.
     * Safe to call multiple times; skips already-ingested paths.
     *
     * @param file the file to ingest
     * @return true if ingestion succeeded, false otherwise
     */
    public boolean ingestFile(File file) {
        String absPath = file.getAbsolutePath();

        // Skip if already ingested in this session
        if (ingestedPaths.contains(absPath)) {
            log.debug("Skipping already-ingested file: {}", absPath);
            return false;
        }

        // Skip unsupported types
        if (!FileUtils.isSupportedFile(file)) {
            return false;
        }

        log.info("Ingesting file: {} (size: {} bytes)", absPath, file.length());

        try {
            // 1. Read raw text from file using appropriate reader
            List<Document> rawDocuments = readDocuments(file);
            if (rawDocuments.isEmpty()) {
                log.warn("No text extracted from file '{}'. Skipping.", file.getName());
                return false;
            }
            log.debug("Extracted {} raw document(s) from '{}'", rawDocuments.size(), file.getName());

            // 2. Determine the argument (topic) from the parent folder name
            String argument = FileUtils.extractArgument(file, Paths.get(props.getDocumentsPath()));

            // 3. Add metadata to each document so we can filter by argument later
            rawDocuments.forEach(doc -> {
                doc.getMetadata().put("argument", argument);
                doc.getMetadata().put("source_file", file.getName());
                doc.getMetadata().put("source_path", absPath);
            });

            // 4. Split documents into chunks
            TokenTextSplitter splitter = new TokenTextSplitter(
                    props.getChunkSize(),
                    props.getChunkOverlap(),
                    5,      // minChunkSizeChars — skip tiny fragments
                    10000,  // maxNumChunks — safety cap
                    true    // keepSeparator
            );
            List<Document> chunks = splitter.apply(rawDocuments);
            log.info("Split '{}' into {} chunks (chunkSize={}, overlap={})",
                    file.getName(), chunks.size(), props.getChunkSize(), props.getChunkOverlap());

            // 5. Store in Qdrant (Spring AI handles embedding via Ollama automatically)
            log.debug("Storing {} chunks in Qdrant for file '{}'...", chunks.size(), file.getName());
            vectorStore.add(chunks);
            log.info("Successfully stored {} chunks for '{}' [argument={}]",
                    chunks.size(), file.getName(), argument);

            // 6. Track ingestion
            ingestedPaths.add(absPath);
            documentCatalogue.add(new DocumentInfo(
                    file.getName(), argument, absPath, file.length()
            ));

            return true;

        } catch (Exception e) {
            log.error("Failed to ingest file '{}': {}", absPath, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Reads a file using the appropriate Spring AI document reader based on extension.
     * - PDF  → PagePdfDocumentReader (best page-aware extraction)
     * - DOCX → TikaDocumentReader    (Apache Tika handles DOCX/DOC/PPTX/etc.)
     * - TXT  → TikaDocumentReader    (works fine for plain text too)
     */
    private List<Document> readDocuments(File file) {
        String name = file.getName().toLowerCase();
        FileSystemResource resource = new FileSystemResource(file);

        if (name.endsWith(".pdf")) {
            log.debug("Using PagePdfDocumentReader for: {}", file.getName());
            PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                    .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                            .withNumberOfBottomTextLinesToDelete(0)
                            .withNumberOfTopPagesToSkipBeforeDelete(0)
                            .build())
                    .withPagesPerDocument(1)
                    .build();
            PagePdfDocumentReader reader = new PagePdfDocumentReader(resource, config);
            return reader.get();

        } else {
            // DOCX and TXT both handled by TikaDocumentReader
            log.debug("Using TikaDocumentReader for: {}", file.getName());
            TikaDocumentReader reader = new TikaDocumentReader(resource);
            return reader.get();
        }
    }

    /** Returns a read-only view of all ingested document metadata. */
    public List<DocumentInfo> getDocumentCatalogue() {
        return Collections.unmodifiableList(documentCatalogue);
    }

    /** Returns the distinct argument (topic) values present in the catalogue. */
    public List<String> getArguments() {
        return documentCatalogue.stream()
                .map(DocumentInfo::getArgument)
                .distinct()
                .sorted()
                .toList();
    }
}
