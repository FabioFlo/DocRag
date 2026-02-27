package com.ex.docrag.service;

import com.ex.docrag.config.DocRagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Monitors the documents root folder (and all sub-folders) for new or modified files.
 * Uses Java NIO WatchService which is OS-native and very efficient.
 *
 * When a new file is detected, it delegates to DocumentIngestionService.ingestFile().
 * Runs in a separate daemon thread so it does not block the main application.
 */
@Service
public class FolderWatcherService {

    private static final Logger log = LoggerFactory.getLogger(FolderWatcherService.class);

    private final DocumentIngestionService ingestionService;
    private final DocRagProperties props;

    private WatchService watchService;
    private ExecutorService executor;

    /** Maps each WatchKey back to the folder it monitors (needed for resolving event paths). */
    private final Map<WatchKey, Path> watchKeyToPath = new HashMap<>();

    public FolderWatcherService(DocumentIngestionService ingestionService, DocRagProperties props) {
        this.ingestionService = ingestionService;
        this.props = props;
    }

    @PostConstruct
    public void start() {
        Path root = Paths.get(props.getDocumentsPath());
        if (!Files.exists(root)) {
            log.warn("FolderWatcherService: documents path '{}' does not exist yet. Watcher not started.", root);
            return;
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
            registerAll(root);

            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "docrag-folder-watcher");
                t.setDaemon(true);
                return t;
            });
            executor.submit(this::watchLoop);

            log.info("FolderWatcherService started. Monitoring: {}", root);

        } catch (IOException e) {
            log.error("Failed to start FolderWatcherService: {}", e.getMessage(), e);
        }
    }

    /**
     * Registers a directory and all its subdirectories with the WatchService.
     */
    private void registerAll(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                WatchKey key = dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                watchKeyToPath.put(key, dir);
                log.debug("Watching directory: {}", dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Main watch loop — blocks on watchService.take() until an event fires.
     */
    private void watchLoop() {
        log.debug("Watch loop running...");
        while (!Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("FolderWatcherService watch loop interrupted. Stopping.");
                break;
            }

            Path dir = watchKeyToPath.get(key);
            if (dir == null) {
                log.warn("WatchKey not recognised — skipping.");
                key.reset();
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    log.warn("WatchService OVERFLOW — some events may have been missed.");
                    continue;
                }

                @SuppressWarnings("unchecked")
                WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                Path eventPath = dir.resolve(pathEvent.context());
                File file = eventPath.toFile();

                log.info("FS event [{}]: {}", kind.name(), eventPath);

                if (file.isDirectory()) {
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                        try {
                            registerAll(eventPath);
                            log.info("Registered new sub-directory for watching: {}", eventPath);
                        } catch (IOException e) {
                            log.error("Failed to register new directory '{}': {}", eventPath, e.getMessage());
                        }
                    }
                } else {
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE ||
                        kind == StandardWatchEventKinds.ENTRY_MODIFY) {

                        try { TimeUnit.MILLISECONDS.sleep(500); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }

                        boolean ingested = ingestionService.ingestFile(file);
                        if (ingested) {
                            log.info("Auto-ingested new file: {}", file.getName());
                        }
                    }
                }
            }

            boolean valid = key.reset();
            if (!valid) {
                log.warn("WatchKey for '{}' is no longer valid (directory may have been deleted).", dir);
                watchKeyToPath.remove(key);
            }
        }
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping FolderWatcherService...");
        if (executor != null) {
            executor.shutdownNow();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.warn("Error closing WatchService: {}", e.getMessage());
            }
        }
    }
}
