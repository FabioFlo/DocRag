package com.ex.docrag.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;

/**
 * Utility methods for file handling.
 */
public class FileUtils {

    private static final Logger log = LoggerFactory.getLogger(FileUtils.class);

    private FileUtils() {} // utility class — no instances

    /**
     * Derives the "argument" (topic) from the file path.
     * Convention: the immediate parent folder of the file is the argument.
     * Example: documents/tax-law/iva-guide.pdf → argument = "tax-law"
     *
     * @param file       the document file
     * @param rootPath   the root documents folder (to detect direct children)
     * @return the argument string, or "general" if the file is at the root level
     */
    public static String extractArgument(File file, Path rootPath) {
        Path parent = file.toPath().getParent();
        if (parent == null || parent.equals(rootPath)) {
            log.debug("File '{}' is at root level — assigning argument 'general'", file.getName());
            return "general";
        }
        String argument = parent.getFileName().toString();
        log.debug("Extracted argument '{}' from file path '{}'", argument, file.getAbsolutePath());
        return argument;
    }

    /**
     * Checks whether a file extension is supported for ingestion.
     * Supported: .pdf, .docx, .txt
     */
    public static boolean isSupportedFile(File file) {
        String name = file.getName().toLowerCase();
        boolean supported = name.endsWith(".pdf") || name.endsWith(".docx") || name.endsWith(".txt");
        if (!supported) {
            log.warn("Skipping unsupported file type: '{}'", file.getName());
        }
        return !supported;
    }
}
