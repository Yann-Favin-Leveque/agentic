package io.github.yannfavinleveque.agentic.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Utility class to extract agent JSON files from JAR resources to a temporary directory.
 * This allows the AgentService to access agent definitions even when running from a packaged JAR.
 */
public class AgentResourceExtractor {

    private static final Logger logger = LoggerFactory.getLogger(AgentResourceExtractor.class);
    private static final String TEMP_AGENTS_FOLDER_PREFIX = "agentic-helper-";

    /**
     * Cache of already-extracted directories keyed by classpath sub-path. Allows several
     * configured paths to coexist in the same JVM (e.g. tests + production paths) without
     * stepping on each other's temp directories.
     */
    private static final Map<String, Path> extractedDirectories = new HashMap<>();

    /**
     * Extracts all agent JSON files from the given classpath sub-path to a temporary directory.
     * If already extracted in this JVM session for the same sub-path, returns the existing directory path.
     *
     * @param classpathSubPath sub-path inside the classpath where agent JSON files live (e.g.
     *                         "agents" or "prompts/agents"). Required, non-null, non-empty.
     * @return Path to the directory containing extracted agent JSON files
     * @throws IOException if extraction fails
     */
    public static synchronized Path extractAgentsFromClasspath(String classpathSubPath) throws IOException {
        if (classpathSubPath == null || classpathSubPath.trim().isEmpty()) {
            throw new IllegalArgumentException("classpathSubPath is required (non-null, non-empty)");
        }

        // Normalize: strip leading/trailing slashes (we add the leading slash later when needed)
        String normalizedSubPath = classpathSubPath.trim();
        while (normalizedSubPath.startsWith("/")) {
            normalizedSubPath = normalizedSubPath.substring(1);
        }
        while (normalizedSubPath.endsWith("/")) {
            normalizedSubPath = normalizedSubPath.substring(0, normalizedSubPath.length() - 1);
        }
        if (normalizedSubPath.isEmpty()) {
            throw new IllegalArgumentException("classpathSubPath cannot be just slashes");
        }

        Path cached = extractedDirectories.get(normalizedSubPath);
        if (cached != null && Files.exists(cached)) {
            logger.debug("♻️  Using existing extracted agents directory for '{}': {}", normalizedSubPath, cached);
            return cached;
        }

        // Per-sub-path temp folder name to avoid collisions when multiple apps coexist on the same host.
        String tempFolderName = TEMP_AGENTS_FOLDER_PREFIX + normalizedSubPath.replace('/', '-');
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), tempFolderName);

        // Clean stale .json files in the temp dir (in case of redeploys with different agent sets)
        if (Files.exists(tempDir)) {
            try (Stream<Path> oldFiles = Files.list(tempDir)) {
                oldFiles.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
            }
        }
        Files.createDirectories(tempDir);

        logger.info("📦 Extracting agent JSON files from JAR classpath '{}' to: {}", normalizedSubPath, tempDir);

        // Find the configured sub-path in the classpath
        URL agentsUrl = AgentResourceExtractor.class.getClassLoader().getResource(normalizedSubPath);

        if (agentsUrl == null) {
            logger.warn("⚠️  No '{}' directory found in classpath", normalizedSubPath);
            extractedDirectories.put(normalizedSubPath, tempDir);
            return tempDir;
        }

        int successCount = 0;

        // Handle both JAR and file system resources
        if (agentsUrl.getProtocol().equals("jar")) {
            successCount = extractFromJar(agentsUrl, tempDir, normalizedSubPath);
        } else if (agentsUrl.getProtocol().equals("file")) {
            successCount = extractFromFileSystem(agentsUrl, tempDir);
        }

        extractedDirectories.put(normalizedSubPath, tempDir);
        logger.info("✅ Successfully extracted {} agent JSON file(s)", successCount);

        return tempDir;
    }

    private static int extractFromJar(URL jarUrl, Path targetDir, String classpathSubPath) throws IOException {
        URI uri = URI.create(jarUrl.toString());
        int successCount = 0;

        try (FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap())) {
            Path agentsPath = fs.getPath("/" + classpathSubPath);

            try (Stream<Path> paths = Files.walk(agentsPath, 1)) {
                for (Path path : paths.toArray(Path[]::new)) {
                    if (Files.isRegularFile(path) && path.toString().endsWith(".json")) {
                        String filename = path.getFileName().toString();
                        Path targetFile = targetDir.resolve(filename);

                        try (InputStream inputStream = Files.newInputStream(path)) {
                            Files.copy(inputStream, targetFile, StandardCopyOption.REPLACE_EXISTING);
                            logger.debug("   ✅ Extracted: {}", filename);
                            successCount++;
                        }
                    }
                }
            }
        }

        return successCount;
    }

    private static int extractFromFileSystem(URL fileUrl, Path targetDir) throws IOException {
        Path agentsPath = Paths.get(URI.create(fileUrl.toString()));
        int successCount = 0;

        try (Stream<Path> paths = Files.walk(agentsPath, 1)) {
            for (Path path : paths.toArray(Path[]::new)) {
                if (Files.isRegularFile(path) && path.toString().endsWith(".json")) {
                    String filename = path.getFileName().toString();
                    Path targetFile = targetDir.resolve(filename);

                    Files.copy(path, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    logger.debug("   ✅ Extracted: {}", filename);
                    successCount++;
                }
            }
        }

        return successCount;
    }

    /**
     * Gets the most recently extracted agent directory path without performing extraction.
     * If multiple sub-paths were extracted, returns one of them (no specific guarantee).
     *
     * @return Path to an extracted agents directory, or null if nothing has been extracted
     */
    public static Path getExtractedAgentDirectory() {
        if (extractedDirectories.isEmpty()) {
            return null;
        }
        return extractedDirectories.values().iterator().next();
    }

    /**
     * Clears all cached directory paths (for testing purposes).
     */
    static void resetCache() {
        extractedDirectories.clear();
    }
}
