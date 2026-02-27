package com.ex.docrag.controller;

import com.ex.docrag.config.DocRagProperties;
import com.ex.docrag.model.DocumentInfo;
import com.ex.docrag.model.QueryResponse;
import com.ex.docrag.service.DocumentIngestionService;
import com.ex.docrag.service.QueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

/**
 * Thymeleaf MVC controller — serves the main web UI at GET /.
 */
@Controller
public class WebController {

    private static final Logger log = LoggerFactory.getLogger(WebController.class);

    private final DocumentIngestionService ingestionService;
    private final QueryService queryService;
    private final DocRagProperties props;

    public WebController(DocumentIngestionService ingestionService,
                         QueryService queryService,
                         DocRagProperties props) {
        this.ingestionService = ingestionService;
        this.queryService = queryService;
        this.props = props;
    }

    /**
     * Main page — displays document list, upload form, and query form.
     */
    @GetMapping("/")
    public String index(Model model) {
        log.debug("GET / — loading main page");

        List<DocumentInfo> docs = ingestionService.getDocumentCatalogue();
        List<String> arguments = ingestionService.getArguments();

        model.addAttribute("documents", docs);
        model.addAttribute("arguments", arguments);
        model.addAttribute("documentCount", docs.size());

        return "index";
    }

    /**
     * Handles file upload from the web form.
     */
    @PostMapping("/upload")
    public String upload(@RequestParam("argument") String argument,
                         @RequestParam("file") MultipartFile file,
                         RedirectAttributes redirectAttributes) {

        log.info("Web upload — argument='{}', file='{}'", argument, file.getOriginalFilename());

        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("uploadError", "Please select a file to upload.");
            return "redirect:/";
        }

        if (argument == null || argument.isBlank()) {
            redirectAttributes.addFlashAttribute("uploadError", "Please enter a topic/argument for this document.");
            return "redirect:/";
        }

        try {
            Path targetDir = Paths.get(props.getDocumentsPath(), argument);
            Files.createDirectories(targetDir);

            Path targetFile = targetDir.resolve(Objects.requireNonNull(file.getOriginalFilename()));
            file.transferTo(targetFile.toFile());
            log.info("Saved uploaded file to: {}", targetFile);

            boolean ingested = ingestionService.ingestFile(targetFile.toFile());

            if (ingested) {
                redirectAttributes.addFlashAttribute("uploadSuccess",
                        "File '" + file.getOriginalFilename() + "' uploaded and indexed under topic '" + argument + "'.");
            } else {
                redirectAttributes.addFlashAttribute("uploadSuccess",
                        "File saved but was skipped during indexing (already indexed or unsupported type).");
            }

        } catch (IOException e) {
            log.error("Web upload failed: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("uploadError",
                    "Upload failed: " + e.getMessage());
        }

        return "redirect:/";
    }

    /**
     * Handles the query form submission.
     */
    @PostMapping("/ask")
    public String ask(@RequestParam("question") String question,
                      @RequestParam(value = "argument", required = false) String argument,
                      Model model) {

        log.info("Web ask — question='{}', argument='{}'", question, argument);

        model.addAttribute("documents", ingestionService.getDocumentCatalogue());
        model.addAttribute("arguments", ingestionService.getArguments());
        model.addAttribute("documentCount", ingestionService.getDocumentCatalogue().size());
        model.addAttribute("lastQuestion", question);
        model.addAttribute("lastArgument", argument);

        if (question == null || question.isBlank()) {
            model.addAttribute("answerError", "Please enter a question.");
            return "index";
        }

        QueryResponse response = queryService.query(question, argument);
        model.addAttribute("queryResponse", response);

        return "index";
    }
}
