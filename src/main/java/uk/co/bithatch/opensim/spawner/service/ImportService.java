package uk.co.bithatch.opensim.spawner.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ImportService {

	private static final Logger LOG = LoggerFactory.getLogger(ImportService.class);
	private final OpenSimService openSimService;

	public ImportService(OpenSimService openSimService) {
		this.openSimService = openSimService;
	}

	public synchronized void importOAR(Path workspaceDir, String region, InputStream archiveStream, String archiveFileName, boolean merge, boolean skipAssets) {
		if (archiveFileName == null || archiveFileName.isBlank()) {
			throw new IllegalArgumentException("archive filename is required.");
		}
		var filename = archiveFileName.trim();

		try {
			Files.createDirectories(workspaceDir);
		}
		catch (IOException ioe) {
			throw new UncheckedIOException("Failed to create workspace dir for importOAR of region " + region+ ".", ioe);
		}

		var destination = workspaceDir.resolve(filename);
		try (var input = archiveStream) {
			Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException ioe) {
			throw new UncheckedIOException("Failed to write imported archive " + destination + ".", ioe);
		}

		LOG.info("Imported OAR '{}' into {}.", filename, region);
		openSimService.loadRegionArchive(region, null, destination.toString(), 
				merge, skipAssets);
	}
	
	public synchronized String importIAR(Path workspaceDir, String first, String last, InputStream archiveStream, String archiveFileName,
			String inventoryPath, String password) {

		if (archiveFileName == null || archiveFileName.isBlank()) {
			throw new IllegalArgumentException("archive filename is required.");
		}
		var filename = archiveFileName.trim();
		var foldername = filename;
		if(foldername.toLowerCase().endsWith(".iar")) {
			foldername = filename.substring(0, filename.length() - 4);
		}

		var targetPath = inventoryPath == null || inventoryPath.isBlank()
				? Path.of("Objects")
				: Path.of(inventoryPath.trim());

		try {
			Files.createDirectories(workspaceDir);
		}
		catch (IOException ioe) {
			throw new UncheckedIOException("Failed to create workspace dir for importIAR of bot " + first + " " + last  + ".", ioe);
		}

		var destination = workspaceDir.resolve(filename);
		try (var input = archiveStream) {
			Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException ioe) {
			throw new UncheckedIOException("Failed to write imported archive " + destination + ".", ioe);
		}

		LOG.info("Imported IAR '{}' into {} {} at '{}'.", filename, first, last, targetPath);
		openSimService.loadInventoryArchive(first, last, targetPath.toString(), password,
				destination.toString());
		
		return targetPath.toString();
	}
}
