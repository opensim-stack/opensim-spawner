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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;
import uk.co.bithatch.opensim.jlib.Strings;
import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.state.StackStateRepository;

@Service
public class UpdateService {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateService.class);
    private static final Duration MANIFEST_CACHE_TTL = Duration.ofMinutes(15);

    private final StackStateRepository gridStateRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
	private final DockerService dockerService;
	private final SpawnerProperties spawnerProperties;

    private volatile Instant lastRefresh = Instant.EPOCH;
    private volatile Map<String, StackContainerUpdateStatus> cachedStatusByContainer = Map.of();

    @Autowired
    public UpdateService(
            StackStateRepository gridStateRepository,
            ObjectMapper objectMapper,
            DockerService dockerService,
            SpawnerProperties spawnerProperties
            ) {
    	this.spawnerProperties = spawnerProperties;
        this.gridStateRepository = gridStateRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.dockerService = dockerService;
    }

    public synchronized Map<String, StackContainerUpdateStatus> containerUpdateStatus(boolean forceRefresh) {
        var stale = Duration.between(lastRefresh, Instant.now()).compareTo(MANIFEST_CACHE_TTL) > 0;
        LOG.info("Stack update snapshot requested: forceRefresh={}, stale={}, cachedCount={}, cacheAgeSeconds={}.",
                forceRefresh,
                stale,
                cachedStatusByContainer.size(),
                Duration.between(lastRefresh, Instant.now()).toSeconds());
        if (forceRefresh || stale || cachedStatusByContainer.isEmpty()) {
            LOG.info("Refreshing stack update snapshot (forceRefresh={}, stale={}, cachedCount={}).",
                    forceRefresh,
                    stale,
                    cachedStatusByContainer.size());
            cachedStatusByContainer = refreshStatusSnapshot();
            lastRefresh = Instant.now();
        }
        return cachedStatusByContainer;
    }

    public synchronized StackContainerUpdateStatus updateContainer(String containerName) {
        var normalizedName = normalizeContainerName(containerName);
        var state = inspectContainerForUpdate(normalizedName);
        if (!state.updateAvailable()) {
            LOG.info("Update check for container {} found no change. targetImage={}, localDigest={}, remoteDigest={}.",
                    normalizedName,
                    state.targetImage(),
                    state.localDigest(),
                    state.remoteDigest());
            return state;
        }

        LOG.info("Updating container {} using targetImage={} (localDigest={}, remoteDigest={}).",
                normalizedName,
                state.targetImage(),
                state.localDigest(),
                state.remoteDigest());
        if (!isLocalImage(state.targetImage())) {
            dockerService.pullImage(state.targetImage());
        }

        if (isSpawnerImage(state.targetImage())) {
            dockerService.scheduleSelfUpdate(state.containerName(), state.targetImage());
            LOG.info("Scheduled self-update worker for container {}. Current process may terminate shortly.",
                    state.containerName());
            return state;
        }

        dockerService.recreateContainer(state.containerName(), state.targetImage());
        var refreshed = inspectContainerForUpdate(normalizedName);
        LOG.info("Updated container {}. newLocalDigest={}, newRemoteDigest={}, updateAvailable={}.",
                normalizedName,
                refreshed.localDigest(),
                refreshed.remoteDigest(),
                refreshed.updateAvailable());
        cachedStatusByContainer = refreshStatusSnapshot();
        lastRefresh = Instant.now();
        return refreshed;
    }

    public synchronized List<StackContainerUpdateStatus> updateAllSequentially() {
        var updates = availableUpdatesOnly();
        var updated = new ArrayList<StackContainerUpdateStatus>();
        for (var candidate : updates) {
            var result = updateContainer(candidate.containerName());
            updated.add(result);
            if (isSpawnerImage(result.targetImage())) {
                LOG.info("Stopping bulk update loop after scheduling spawner self-update for {}.",
                        result.containerName());
                break;
            }
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
                    var updated = updateContainer(candidate.containerName());
                    if (isSpawnerImage(updated.targetImage())) {
                        LOG.info("Stopping automatic update loop after scheduling spawner self-update for {}.",
                                updated.containerName());
                        break;
                    }
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
        var containerNames = dockerService.listStackContainers();
        LOG.info("Refreshing stack update snapshot for {} container(s).", containerNames.size());
        for (var containerName : containerNames) {
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
        var imageFromConfig = normalize(inspect.image());
        var targetImage = resolveTargetImage(imageFromConfig, inspect.labels());
        var localDigest = dockerService.resolveLocalDigest(targetImage);
        var remoteDigest = isLocalImage(targetImage) ? normalize(inspect.imageId()) : resolveRemoteDigest(targetImage);
        var updateAvailable = isLocalImage(targetImage)
                ? shouldUpdateLocal(localDigest, remoteDigest)
                : shouldUpdate(localDigest, remoteDigest);
        if (isLocalImage(targetImage)) {
            LOG.info("Local image update check for {}: imageRef={}, labelRef={}, runtimeImageId={}, builtImageId={}, updateAvailable={}.",
                    containerName,
                    imageFromConfig,
                    normalize(inspect.labels() == null ? null : inspect.labels().get(DockerJavaService.LABEL_IMAGE_REF)),
                    remoteDigest,
                    localDigest,
                    updateAvailable);
        } else {
            LOG.info("Remote image update check for {}: imageRef={}, targetImage={}, localDigest={}, remoteDigest={}, updateAvailable={}.",
                    containerName,
                    imageFromConfig,
                    targetImage,
                    localDigest,
                    remoteDigest,
                    updateAvailable);
        }
        return new StackContainerUpdateStatus(containerName, targetImage, updateAvailable, localDigest, remoteDigest);
    }

    private String resolveTargetImage(String imageFromConfig, Map<String, String> labels) {
        var fromLabel = labels == null ? "" : normalize(labels.get(DockerJavaService.LABEL_IMAGE_REF));
        if (!fromLabel.isBlank()) {
            return fromLabel;
        }

        var image = normalize(imageFromConfig);
        if (image.isBlank()) {
            return "";
        }

        return DockerService.toTaggedImage(image, configuredTag());
    }

    private static boolean isLocalImage(String imageRef) {
        if (imageRef == null || imageRef.isBlank()) {
            return false;
        }
        return "local".equalsIgnoreCase(imageTag(imageRef));
    }

    private static boolean shouldUpdateLocal(String localDigest, String currentImageId) {
        if (currentImageId == null || currentImageId.isBlank()) {
            LOG.info("Local image update check skipped: runtime image ID is blank (builtImageId={}).", localDigest);
            return false;
        }
        if (localDigest == null || localDigest.isBlank() || DIGEST_UNKNOWN.equals(localDigest)) {
            LOG.info("Local image update check skipped: built image ID is unavailable (currentImageId={}).", currentImageId);
            return false;
        }
        LOG.info("Comparing local image IDs: builtImageId={} vs currentImageId={}.", localDigest, currentImageId);
        return !localDigest.equals(currentImageId);
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
        return normalize(Strings.firstNonBlank(gridStateRepository.get().getUpdates().getTag(), spawnerProperties.getOpensimTag(), "latest"));
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
