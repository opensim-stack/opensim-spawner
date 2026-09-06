package uk.co.bithatch.opensim.spawner.service;

import static uk.co.bithatch.opensim.jlib.Strings.normalize;
import static uk.co.bithatch.opensim.jlib.Strings.urlEncode;
import static uk.co.bithatch.opensim.spawner.service.DockerService.DIGEST_UNKNOWN;
import static uk.co.bithatch.opensim.spawner.service.DockerService.dockerHubRepository;
import static uk.co.bithatch.opensim.spawner.service.DockerService.imageTag;
import static uk.co.bithatch.opensim.spawner.service.DockerService.isDockerHubImage;
import static uk.co.bithatch.opensim.spawner.service.DockerService.normalizeContainerName;

import java.net.URI;
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

import jakarta.annotation.PreDestroy;
import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.state.GridStateRepository;

@Service
public class UpdateService {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateService.class);
    private static final Duration MANIFEST_CACHE_TTL = Duration.ofMinutes(15);

    private final SpawnerProperties properties;
    private final GridStateRepository gridStateRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
	private final DockerService dockerService;

    private volatile Instant lastRefresh = Instant.EPOCH;
    private volatile Map<String, StackContainerUpdateStatus> cachedStatusByContainer = Map.of();

    @Autowired
    public UpdateService(SpawnerProperties properties,
            GridStateRepository gridStateRepository,
            ObjectMapper objectMapper,
            DockerService dockerService) {
        this.properties = properties;
        this.gridStateRepository = gridStateRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.dockerService = dockerService;
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

        dockerService.pullImage(state.targetImage());
        dockerService.recreateContainer(state.containerName(), state.targetImage());
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
        for (var containerName : dockerService.listStackContainers()) {
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
        var inspect = dockerService.inspect(containerName);
        var imageFromConfig = inspect.image();
        var targetImage = DockerService.toTaggedImage(imageFromConfig, configuredTag());
        var localDigest = dockerService.resolveLocalDigest(targetImage); 
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

   

    private static boolean isSpawnerImage(String imageRef) {
        var repository = DockerService.repositoryPart(imageRef);
        if (repository.isBlank()) {
            return false;
        }
        var tail = repository.substring(repository.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return "opensim-spawner".equals(tail);
    }

    @PreDestroy
    public void shutdown() {
    }

    public record StackContainerUpdateStatus(
            String containerName,
            String targetImage,
            boolean updateAvailable,
            String localDigest,
            String remoteDigest) {
    }
}
