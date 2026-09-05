package uk.co.bithatch.opensim.spawner.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.slf4j.Logger;
import org.springframework.core.io.ClassPathResource;

final class ArchiveWorkspaceResolver {

    private ArchiveWorkspaceResolver() {
    }

    static Path resolveArchivePath(String resourcePath, Path workspaceDir, List<Path> writtenFiles, Logger log) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("Appearance archive path is blank.");
        }

        var rawPath = resourcePath.trim();
        var candidate = Path.of(rawPath);
        if (Files.exists(candidate)) {
            return candidate.toAbsolutePath().normalize();
        }

        var classpathPath = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        if (classpathPath.isBlank()) {
            throw new IllegalArgumentException("Appearance archive path is blank.");
        }

        var fileName = Path.of(classpathPath).getFileName();
        if (fileName == null || fileName.toString().isBlank()) {
            throw new IllegalArgumentException("Appearance archive path '" + resourcePath + "' does not contain a file name.");
        }

        var targetPath = workspaceDir.resolve(fileName.toString());
        try {
            Files.createDirectories(workspaceDir);
            if (!Files.exists(targetPath)) {
                var resource = new ClassPathResource(classpathPath);
                if (!resource.exists()) {
                    throw new IllegalStateException(
                            "Appearance archive '" + resourcePath + "' was not found on filesystem or classpath.");
                }
                try (var input = resource.getInputStream()) {
                    Files.copy(input, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
                if (writtenFiles != null) {
                    writtenFiles.add(targetPath);
                }
                if (log != null) {
                    log.info("Copied appearance archive resource '{}' to '{}'.", resourcePath, targetPath);
                }
            }
            return targetPath.toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to copy appearance archive '" + resourcePath + "' to workspace.", e);
        }
    }
}
