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
import java.util.stream.Stream;

/**
 * Utility class to extract agent JSON files from JAR resources to a temporary directory.
 * This allows the AgentService to access agent definitions even when running from a packaged JAR.
 */
public class AgentResourceExtractor {

    private static final Logger logger = LoggerFactory.getLogger(AgentResourceExtractor.class);
    private static final String AGENTS_CLASSPATH_PATTERN = "classpath:agents/*.json";
    private static final String TEMP_AGENTS_FOLDER = "agentic-helper-agents";

    private static Path extractedAgentDirectory = null;

    /**
     * Extracts all agent JSON files from the classpath to a temporary directory.
     * If already extracted in this JVM session, returns the existing directory path.
     *
     * @return Path to the directory containing extracted agent JSON files
     * @throws IOException if extraction fails
     */
    public static synchronized Path extractAgentsFromClasspath() throws IOException {
        if (extractedAgentDirectory != null && Files.exists(extractedAgentDirectory)) {
            logger.debug("♻️  Using existing extracted agents directory: {}", extractedAgentDirectory);
            return extractedAgentDirectory;
        }

        // Create temporary directory for agents
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), TEMP_AGENTS_FOLDER);
        Files.createDirectories(tempDir);

        logger.info("📦 Extracting agent JSON files from JAR to: {}", tempDir);

        // Find all agent JSON files in classpath
        URL agentsUrl = AgentResourceExtractor.class.getClassLoader().getResource("agents");

        if (agentsUrl == null) {
            logger.warn("⚠️  No 'agents' directory found in classpath");
            extractedAgentDirectory = tempDir;
            return tempDir;
        }

        int successCount = 0;

        // Handle both JAR and file system resources
        if (agentsUrl.getProtocol().equals("jar")) {
            // Extract from JAR
            successCount = extractFromJar(agentsUrl, tempDir);
        } else if (agentsUrl.getProtocol().equals("file")) {
            // Copy from file system
            successCount = extractFromFileSystem(agentsUrl, tempDir);
        }

        extractedAgentDirectory = tempDir;
        logger.info("✅ Successfully extracted {} agent JSON file(s)", successCount);

        return tempDir;
    }

    private static int extractFromJar(URL jarUrl, Path targetDir) throws IOException {
        URI uri = URI.create(jarUrl.toString());
        int successCount = 0;

        try (FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap())) {
            Path agentsPath = fs.getPath("/agents");

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
     * Gets the extracted agent directory path without performing extraction.
     *
     * @return Path to extracted agents directory, or null if not yet extracted
     */
    public static Path getExtractedAgentDirectory() {
        return extractedAgentDirectory;
    }

    /**
     * Clears the cached directory path (for testing purposes).
     */
    static void resetCache() {
        extractedAgentDirectory = null;
    }
}
