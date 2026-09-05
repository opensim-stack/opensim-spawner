package uk.co.bithatch.opensim.spawner.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

import jakarta.annotation.PreDestroy;
import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.state.GridStateRepository;

@Service
public class UpdateService {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateService.class);
    private static final String DIGEST_UNKNOWN = "unknown";
    private static final Duration MANIFEST_CACHE_TTL = Duration.ofMinutes(15);

    private final SpawnerProperties properties;
    private final GridStateRepository gridStateRepository;
    private final ObjectMapper objectMapper;
    private final DockerClient dockerClient;
    private final HttpClient httpClient;

    private volatile Instant lastRefresh = Instant.EPOCH;
    private volatile Map<String, StackContainerUpdateStatus> cachedStatusByContainer = Map.of();

    @Autowired
    public UpdateService(SpawnerProperties properties,
            GridStateRepository gridStateRepository,
            ObjectMapper objectMapper) {
        this(properties, gridStateRepository, objectMapper, buildDockerClient());
    }

    UpdateService(SpawnerProperties properties,
            GridStateRepository gridStateRepository,
            ObjectMapper objectMapper,
            DockerClient dockerClient) {
        this.properties = properties;
        this.gridStateRepository = gridStateRepository;
        this.objectMapper = objectMapper;
        this.dockerClient = dockerClient;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public synchronized Map<String, StackContainerUpdateStatus> containerUpdateStatus(boolean forceRefresh) {
        var stale = Duration.between(lastRefresh, Instant.now()).compareTo(MANIFEST_CACHE_TTL) > 0;
        if (forceRefresh || stale || cachedStatusByContainer.isEmpty()) {
            cachedStatusByContainer = refreshStatusSnapshot();
            lastRefresh = Instant.now();
        }
        return cachedStatusByContainer;
    }

    public synchronized StackContainerUpdateStatus updateContainer(String containerName) {
        var normalizedName = normalizeContainerName(containerName);
        var state = inspectContainerForUpdate(normalizedName);
        if (!state.updateAvailable()) {
            return state;
        }

        pullImage(state.targetImage());
        recreateContainer(state.containerName(), state.targetImage());
        var refreshed = inspectContainerForUpdate(normalizedName);
        cachedStatusByContainer = refreshStatusSnapshot();
        lastRefresh = Instant.now();
        return refreshed;
    }

    public synchronized List<StackContainerUpdateStatus> updateAllSequentially() {
        var updates = availableUpdatesOnly();
        var updated = new ArrayList<StackContainerUpdateStatus>();
        for (var candidate : updates) {
            updated.add(updateContainer(candidate.containerName()));
        }
        return updated;
    }

    @Scheduled(cron = "${spawner.updates.cron:0 0 3 * * *}")
    public void checkForUpdatesDaily() {
        try {
            var latest = containerUpdateStatus(true);
            var automatic = gridStateRepository.get().getUpdates().isAutomaticUpdates();
            if (!automatic) {
                LOG.info("Daily stack update check completed. Automatic updates are disabled.");
                return;
            }

            var candidates = applySpawnerPriority(latest).values().stream()
                    .filter(StackContainerUpdateStatus::updateAvailable)
                    .sorted(Comparator.comparing(StackContainerUpdateStatus::containerName))
                    .toList();
            if (candidates.isEmpty()) {
                LOG.info("Daily stack update check completed. No updates available.");
                return;
            }

            LOG.info("Daily stack update check found {} container update(s). Applying sequentially.", candidates.size());
            for (var candidate : candidates) {
                try {
                    updateContainer(candidate.containerName());
                } catch (RuntimeException e) {
                    LOG.warn("Automatic update failed for container {}.", candidate.containerName(), e);
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Daily stack update check failed.", e);
        }
    }

    private List<StackContainerUpdateStatus> availableUpdatesOnly() {
        return applySpawnerPriority(containerUpdateStatus(true)).values().stream()
                .filter(StackContainerUpdateStatus::updateAvailable)
                .sorted(Comparator.comparing(StackContainerUpdateStatus::containerName))
                .toList();
    }

    private Map<String, StackContainerUpdateStatus> refreshStatusSnapshot() {
        var byContainer = new LinkedHashMap<String, StackContainerUpdateStatus>();
        for (var container : dockerClient.listContainersCmd().withShowAll(true).exec()) {
            var containerName = primaryName(container == null ? null : container.getNames());
            if (containerName == null || !isTrackedContainer(containerName)) {
                continue;
            }
            try {
                var status = inspectContainerForUpdate(containerName);
                byContainer.put(containerName, status);
            } catch (RuntimeException e) {
                LOG.warn("Could not determine update status for container {}.", containerName, e);
                byContainer.put(containerName,
                        new StackContainerUpdateStatus(containerName, "", false, DIGEST_UNKNOWN, DIGEST_UNKNOWN));
            }
        }
        return applySpawnerPriority(byContainer);
    }

    private StackContainerUpdateStatus inspectContainerForUpdate(String containerName) {
        var inspect = dockerClient.inspectContainerCmd(containerName).exec();
        var imageFromConfig = inspect.getConfig() == null ? "" : String.valueOf(inspect.getConfig().getImage());
        var targetImage = toTaggedImage(imageFromConfig, configuredTag());
        var localDigest = resolveLocalDigest(targetImage);
        var remoteDigest = resolveRemoteDigest(targetImage);
        var updateAvailable = shouldUpdate(localDigest, remoteDigest);
        return new StackContainerUpdateStatus(containerName, targetImage, updateAvailable, localDigest, remoteDigest);
    }

    private static boolean shouldUpdate(String localDigest, String remoteDigest) {
        if (remoteDigest == null || remoteDigest.isBlank() || DIGEST_UNKNOWN.equals(remoteDigest)) {
            return false;
        }
        if (localDigest == null || localDigest.isBlank() || DIGEST_UNKNOWN.equals(localDigest)) {
            return true;
        }
        return !localDigest.equals(remoteDigest);
    }

    private Map<String, StackContainerUpdateStatus> applySpawnerPriority(Map<String, StackContainerUpdateStatus> original) {
        if (original.isEmpty()) {
            return original;
        }

        var prioritized = new LinkedHashMap<String, StackContainerUpdateStatus>(original);
        var spawnerUpdates = prioritized.values().stream()
                .filter(item -> item.updateAvailable() && isSpawnerImage(item.targetImage()))
                .sorted(Comparator.comparing(StackContainerUpdateStatus::containerName))
                .toList();
        if (spawnerUpdates.isEmpty()) {
            return prioritized;
        }

        var result = new LinkedHashMap<String, StackContainerUpdateStatus>();
        for (var update : spawnerUpdates) {
            result.put(update.containerName(), update);
        }
        for (var entry : prioritized.entrySet()) {
            if (!result.containsKey(entry.getKey())) {
                var current = entry.getValue();
                result.put(entry.getKey(), new StackContainerUpdateStatus(
                        current.containerName(),
                        current.targetImage(),
                        false,
                        current.localDigest(),
                        current.remoteDigest()));
            }
        }
        return result;
    }

    private void recreateContainer(String containerName, String targetImage) {
        var inspect = dockerClient.inspectContainerCmd(containerName).exec();
        var oldContainerId = inspect.getId();
        var oldContainerName = trimLeadingSlash(inspect.getName());
        var preservedHostConfig = inspect.getHostConfig();
        var preservedAliases = collectNetworkAliases(inspect);

        dockerClient.stopContainerCmd(oldContainerId).exec();
        dockerClient.removeContainerCmd(oldContainerId).withForce(true).exec();

        String createdContainerId = null;
        try {
            var create = dockerClient.createContainerCmd(targetImage)
                    .withName(oldContainerName)
                    .withHostConfig(preservedHostConfig);
            if (!preservedAliases.isEmpty()) {
                create.withAliases(preservedAliases.toArray(String[]::new));
            }

            var config = inspect.getConfig();
            if (config != null) {
                if (config.getEnv() != null) {
                    create.withEnv(config.getEnv());
                }
                if (config.getCmd() != null) {
                    create.withCmd(config.getCmd());
                }
                if (config.getEntrypoint() != null) {
                    create.withEntrypoint(config.getEntrypoint());
                }
                if (config.getWorkingDir() != null && !config.getWorkingDir().isBlank()) {
                    create.withWorkingDir(config.getWorkingDir());
                }
                if (config.getUser() != null && !config.getUser().isBlank()) {
                    create.withUser(config.getUser());
                }
                if (config.getDomainName() != null && !config.getDomainName().isBlank()) {
                    create.withDomainName(config.getDomainName());
                }
                if (config.getHostName() != null && !config.getHostName().isBlank()) {
                    create.withHostName(config.getHostName());
                }
                if (config.getLabels() != null && !config.getLabels().isEmpty()) {
                    create.withLabels(config.getLabels());
                }
                if (config.getExposedPorts() != null && config.getExposedPorts().length > 0) {
                    create.withExposedPorts(config.getExposedPorts());
                }
                create.withTty(Boolean.TRUE.equals(config.getTty()));
            }

            var created = create.exec();
            createdContainerId = created.getId();
            dockerClient.startContainerCmd(createdContainerId).exec();
        } catch (RuntimeException e) {
            if (createdContainerId != null) {
                try {
                    dockerClient.removeContainerCmd(createdContainerId).withForce(true).exec();
                } catch (RuntimeException ignored) {
                    // Best effort cleanup of failed replacement container.
                }
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to replace container " + oldContainerName + " with updated image: " + e.getMessage(), e);
        }
    }

    private static List<String> collectNetworkAliases(
            com.github.dockerjava.api.command.InspectContainerResponse inspect) {
        var aliases = new ArrayList<String>();
        var networkSettings = inspect == null ? null : inspect.getNetworkSettings();
        var networks = networkSettings == null ? null : networkSettings.getNetworks();
        if (networks == null || networks.isEmpty()) {
            return aliases;
        }

        for (var entry : networks.entrySet()) {
            var endpoint = entry.getValue();
            if (endpoint == null || endpoint.getAliases() == null) {
                continue;
            }

            for (var alias : endpoint.getAliases()) {
                var normalized = normalize(alias);
                if (!normalized.isBlank() && !aliases.contains(normalized)) {
                    aliases.add(normalized);
                }
            }
        }
        return aliases;
    }

    private void pullImage(String image) {
        try {
            var command = dockerClient.pullImageCmd(image);
            var updates = gridStateRepository.get().getUpdates();
            var username = normalize(updates.getDockerHubUsername());
            var token = normalize(updates.getDockerHubToken());
            if (!username.isBlank() && !token.isBlank()) {
                command = command.withAuthConfig(new AuthConfig()
                        .withUsername(username)
                        .withPassword(token)
                        .withRegistryAddress("https://index.docker.io/v1/"));
            }
            command.start().awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Interrupted while pulling Docker image " + image + ".", e);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to pull Docker image " + image + ": " + e.getMessage(), e);
        }
    }

    private String resolveLocalDigest(String targetImage) {
        try {
            var inspect = dockerClient.inspectImageCmd(targetImage).exec();
            var repoDigests = inspect == null ? null : inspect.getRepoDigests();
            if (repoDigests == null || repoDigests.isEmpty()) {
                return DIGEST_UNKNOWN;
            }

            var repository = dockerHubRepository(targetImage);
            for (var repoDigest : repoDigests) {
                if (repoDigest == null || !repoDigest.contains("@")) {
                    continue;
                }
                var parts = repoDigest.split("@", 2);
                if (parts.length != 2) {
                    continue;
                }
                var digestRepository = normalizeDockerIoRepository(parts[0]);
                if (!repository.equals(digestRepository)) {
                    continue;
                }
                var digest = parts[1].trim();
                if (!digest.isBlank()) {
                    return digest;
                }
            }

            var first = repoDigests.get(0);
            if (first != null && first.contains("@")) {
                return first.split("@", 2)[1].trim();
            }
            return DIGEST_UNKNOWN;
        } catch (NotFoundException e) {
            return DIGEST_UNKNOWN;
        } catch (RuntimeException e) {
            LOG.warn("Could not inspect local image digest for {}.", targetImage, e);
            return DIGEST_UNKNOWN;
        }
    }

    private String resolveRemoteDigest(String targetImage) {
        try {
            if (!isDockerHubImage(targetImage)) {
                return DIGEST_UNKNOWN;
            }

            var repository = dockerHubRepository(targetImage);
            var tag = imageTag(targetImage);
            var token = dockerHubBearerToken(repository);
            if (token.isBlank()) {
                return DIGEST_UNKNOWN;
            }

            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://registry-1.docker.io/v2/" + repository + "/manifests/"
                            + urlEncode(tag)))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept",
                            "application/vnd.docker.distribution.manifest.v2+json,application/vnd.docker.distribution.manifest.list.v2+json")
                    .header("Authorization", "Bearer " + token)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.info("Docker Hub manifest request for {}/{} returned status {}.", repository, tag,
                        response.statusCode());
                return DIGEST_UNKNOWN;
            }

            var digest = response.headers().firstValue("Docker-Content-Digest").orElse("").trim();
            return digest.isEmpty() ? DIGEST_UNKNOWN : digest;
        } catch (Exception e) {
            LOG.warn("Could not resolve remote image digest for {}.", targetImage, e);
            return DIGEST_UNKNOWN;
        }
    }

    private String dockerHubBearerToken(String repository) throws Exception {
        var scope = "repository:" + repository + ":pull";
        var uri = URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=" + urlEncode(scope));
        var builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(15))
                .GET();

        var updates = gridStateRepository.get().getUpdates();
        var username = normalize(updates.getDockerHubUsername());
        var token = normalize(updates.getDockerHubToken());
        if (!username.isBlank() && !token.isBlank()) {
            var basic = Base64.getEncoder().encodeToString((username + ":" + token).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + basic);
        }

        var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            LOG.info("Docker Hub auth token request for {} returned status {}.", repository, response.statusCode());
            return "";
        }

        JsonNode body = objectMapper.readTree(response.body());
        return body.path("token").asText("");
    }

    private String configuredTag() {
        return normalize(gridStateRepository.get().getUpdates().getTag(), "latest");
    }

    private boolean isTrackedContainer(String containerName) {
        var name = normalize(containerName);
        if (name.isBlank()) {
            return false;
        }
        var prefix = configuredProjectPrefix();
        if (!name.startsWith(prefix)) {
            return false;
        }
        return !name.matches(".*-init-[0-9]+$");
    }

    private String configuredProjectPrefix() {
        var prefix = properties.getComposeProjectName();
        if (prefix == null || prefix.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "COMPOSE_PROJECT_NAME is not configured for stack container discovery.");
        }
        return prefix.trim();
    }

    private static String normalizeContainerName(String containerName) {
        var normalized = normalize(containerName);
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: container.");
        }
        return normalized;
    }

    private static String toTaggedImage(String imageRef, String tag) {
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

    private static boolean isDockerHubImage(String imageRef) {
        var repository = repositoryPart(imageRef);
        var slash = repository.indexOf('/');
        if (slash < 0) {
            return true;
        }
        var firstSegment = repository.substring(0, slash);
        return !firstSegment.contains(".") && !firstSegment.contains(":") && !"localhost".equals(firstSegment);
    }

    private static String dockerHubRepository(String imageRef) {
        return normalizeDockerIoRepository(repositoryPart(imageRef));
    }

    private static String normalizeDockerIoRepository(String repository) {
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

    private static String repositoryPart(String imageRef) {
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

    private static String imageTag(String imageRef) {
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

    private static boolean isSpawnerImage(String imageRef) {
        var repository = repositoryPart(imageRef);
        if (repository.isBlank()) {
            return false;
        }
        var tail = repository.substring(repository.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return "opensim-spawner".equals(tail);
    }

    private static String primaryName(String[] names) {
        if (names == null || names.length == 0) {
            return null;
        }
        for (var rawName : names) {
            if (rawName == null || rawName.isBlank()) {
                continue;
            }
            return trimLeadingSlash(rawName);
        }
        return null;
    }

    private static String trimLeadingSlash(String name) {
        if (name == null) {
            return "";
        }
        return name.startsWith("/") ? name.substring(1) : name;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalize(String value, String fallback) {
        var normalized = normalize(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static DockerClient buildDockerClient() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        return DockerClientBuilder.getInstance(config).withDockerHttpClient(httpClient).build();
    }

    @PreDestroy
    public void shutdown() {
        try {
            dockerClient.close();
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    public record StackContainerUpdateStatus(
            String containerName,
            String targetImage,
            boolean updateAvailable,
            String localDigest,
            String remoteDigest) {
    }
}
