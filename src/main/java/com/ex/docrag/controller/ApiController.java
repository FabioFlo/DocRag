package com.ex.docrag.controller;

import com.ex.docrag.config.DocRagProperties;
import com.ex.docrag.model.DocumentInfo;
import com.ex.docrag.model.QueryRequest;
import com.ex.docrag.model.QueryResponse;
import com.ex.docrag.service.DocumentIngestionService;
import com.ex.docrag.service.QueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * REST API controller.
 *
 * Endpoints:
 *   GET  /api/documents            — list all indexed documents
 *   GET  /api/arguments            — list all known argument/topic values
 *   POST /api/upload?argument=xxx  — upload a file directly via HTTP
 *   POST /api/query                — ask a question, get an answer
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final DocumentIngestionService ingestionService;
    private final QueryService queryService;
    private final DocRagProperties props;

    public ApiController(DocumentIngestionService ingestionService, QueryService queryService,
                         DocRagProperties props) {
        this.ingestionService = ingestionService;
        this.queryService = queryService;
        this.props = props;
    }

    /** Returns the complete document catalogue. */
    @GetMapping("/documents")
    public ResponseEntity<List<DocumentInfo>> listDocuments() {
        log.debug("GET /api/documents");
        return ResponseEntity.ok(ingestionService.getDocumentCatalogue());
    }

    /** Returns the distinct argument/topic values found across all indexed documents. */
    @GetMapping("/arguments")
    public ResponseEntity<List<String>> listArguments() {
        log.debug("GET /api/arguments");
        return ResponseEntity.ok(ingestionService.getArguments());
    }

    /**
     * Uploads a file via HTTP multipart and ingests it.
     * The file is saved under documents-path/{argument}/{filename}.
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("argument") String argument,
            @RequestParam("file") MultipartFile file) {

        log.info("POST /api/upload — argument='{}', file='{}'", argument, file.getOriginalFilename());

        if (file.isEmpty()) {
            log.warn("Upload rejected: file is empty.");
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty."));
        }

        try {
            Path targetDir = Paths.get(props.getDocumentsPath(), argument);
            Files.createDirectories(targetDir);

            Path targetFile = targetDir.resolve(file.getOriginalFilename());
            file.transferTo(targetFile.toFile());
            log.info("File saved to: {}", targetFile);

            boolean success = ingestionService.ingestFile(targetFile.toFile());

            if (success) {
                return ResponseEntity.ok(Map.of(
                        "status", "ingested",
                        "file", file.getOriginalFilename(),
                        "argument", argument
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                        "status", "skipped",
                        "reason", "File may already be indexed or unsupported type."
                ));
            }

        } catch (IOException e) {
            log.error("Upload failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }

    /**
     * Accepts a JSON question and returns the LLM-generated answer with source chunks.
     */
    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(@RequestBody QueryRequest request) {
        log.info("POST /api/query — question='{}'", request.getQuestion());

        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            log.warn("Query rejected: empty question.");
            return ResponseEntity.badRequest().build();
        }

        QueryResponse response = queryService.query(request.getQuestion(), request.getArgument());
        return ResponseEntity.ok(response);
    }
}
