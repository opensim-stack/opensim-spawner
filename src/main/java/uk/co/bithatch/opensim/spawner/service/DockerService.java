package uk.co.bithatch.opensim.spawner.service;

import static uk.co.bithatch.opensim.jlib.Strings.normalize;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import uk.co.bithatch.opensim.spawner.domain.ContainerSpec;

public interface DockerService {
	
	public interface ContainerUpdateContext {
		String[] env();
		
		void withEnv(String[] env);
	}
	
	String DIGEST_UNKNOWN = "unknown";

	List<String> createContainers(Collection<ContainerSpec> specs);

	void startContainers(List<String> containerIds);

	void stopContainers(List<String> containerIds);

	void restartContainers(List<String> containerIds);

	void attachContainerLogs(List<String> containerIds);

	List<ContainerStatus> getContainerStatuses(List<String> containerIds);

	void removeContainers(List<String> containerIds);

	void removeVolumesBySuffix(String suffix);

	List<String> listStackContainers();

	Map<String, String> getContainerVars(String ref);
	
	ContainerDetails inspect(String containerName);

	default void recreateContainer(String containerName) {
		recreateContainer(containerName, context -> {});
	}

	default void recreateContainer(String containerName, Consumer<ContainerUpdateContext> context) {
		recreateContainer(containerName, inspect(containerName).image(), context);
	}

	default void recreateContainer(String containerName, String targetImage) {
		recreateContainer(containerName, targetImage, context -> {});
	}

	void recreateContainer(String containerName, String targetImage, Consumer<ContainerUpdateContext> context);

	void pullImage(String image);

	String resolveLocalDigest(String targetImage);

	static String normalizeContainerName(String containerName) {
		var normalized = normalize(containerName);
		if (normalized.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: container.");
		}
		return normalized;
	}

	static String toTaggedImage(String imageRef, String tag) {
		var normalizedImage = normalize(imageRef);
		if (normalizedImage.isBlank()) {
			return "";
		}

		var imageWithoutDigest = normalizedImage.contains("@")
				? normalizedImage.substring(0, normalizedImage.indexOf('@'))
				: normalizedImage;
		var lastSlash = imageWithoutDigest.lastIndexOf('/');
		var lastColon = imageWithoutDigest.lastIndexOf(':');
		var hasTag = lastColon > lastSlash;
		var base = hasTag ? imageWithoutDigest.substring(0, lastColon) : imageWithoutDigest;
		return base + ":" + normalize(tag, "latest");
	}

	static boolean isDockerHubImage(String imageRef) {
		var repository = repositoryPart(imageRef);
		var slash = repository.indexOf('/');
		if (slash < 0) {
			return true;
		}
		var firstSegment = repository.substring(0, slash);
		return !firstSegment.contains(".") && !firstSegment.contains(":") && !"localhost".equals(firstSegment);
	}

	static String dockerHubRepository(String imageRef) {
		return normalizeDockerIoRepository(repositoryPart(imageRef));
	}

	static String normalizeDockerIoRepository(String repository) {
		var normalized = normalize(repository).toLowerCase(Locale.ROOT);
		if (normalized.startsWith("docker.io/")) {
			normalized = normalized.substring("docker.io/".length());
		}
		if (normalized.startsWith("index.docker.io/")) {
			normalized = normalized.substring("index.docker.io/".length());
		}
		if (!normalized.contains("/")) {
			return "library/" + normalized;
		}
		return normalized;
	}

	static String repositoryPart(String imageRef) {
		var normalized = normalize(imageRef);
		if (normalized.isBlank()) {
			return "";
		}

		var noDigest = normalized.contains("@") ? normalized.substring(0, normalized.indexOf('@')) : normalized;
		var lastSlash = noDigest.lastIndexOf('/');
		var lastColon = noDigest.lastIndexOf(':');
		if (lastColon > lastSlash) {
			return noDigest.substring(0, lastColon);
		}
		return noDigest;
	}

	static String imageTag(String imageRef) {
		var normalized = normalize(imageRef);
		if (normalized.isBlank()) {
			return "latest";
		}

		var noDigest = normalized.contains("@") ? normalized.substring(0, normalized.indexOf('@')) : normalized;
		var lastSlash = noDigest.lastIndexOf('/');
		var lastColon = noDigest.lastIndexOf(':');
		if (lastColon > lastSlash) {
			return noDigest.substring(lastColon + 1);
		}
		return "latest";
	}
}
