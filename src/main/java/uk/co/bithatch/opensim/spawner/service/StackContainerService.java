package uk.co.bithatch.opensim.spawner.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;

@Service
public class StackContainerService {

    private final SpawnerProperties properties;
    private final DockerClient dockerClient;

    @Autowired
    public StackContainerService(SpawnerProperties properties) {
        this(properties, buildDockerClient());
    }

    StackContainerService(SpawnerProperties properties, DockerClient dockerClient) {
        this.properties = properties;
        this.dockerClient = dockerClient;
    }

    public List<StackContainerView> listStackContainers() {
        var projectPrefix = configuredProjectPrefix();
        var containers = dockerClient.listContainersCmd().withShowAll(true).exec();
        var response = new ArrayList<StackContainerView>();

        for (var container : containers) {
            var containerName = primaryName(container == null ? null : container.getNames());
            if (containerName == null || !containerName.startsWith(projectPrefix)) {
                continue;
            }

            var state = normalizeState(container == null ? null : container.getState(),
                    container == null ? null : container.getStatus());
            response.add(new StackContainerView(
                    containerName,
                    state,
                    "running".equalsIgnoreCase(container == null ? null : container.getState())));
        }

        response.sort(Comparator.comparing(StackContainerView::containerName));
        return response;
    }

    public StackContainerView applyAction(String containerName, String action) {
        var normalizedName = normalizeContainerName(containerName);
        var normalizedAction = normalizeAction(action);

        try {
            dockerClient.inspectContainerCmd(normalizedName).exec();
        } catch (NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Container not found: " + normalizedName, e);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to inspect container " + normalizedName + ": " + e.getMessage(), e);
        }

        try {
            switch (normalizedAction) {
                case "start" -> dockerClient.startContainerCmd(normalizedName).exec();
                case "stop" -> dockerClient.stopContainerCmd(normalizedName).exec();
                case "restart" -> dockerClient.restartContainerCmd(normalizedName).exec();
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unsupported action '" + action + "'. Supported actions: start, stop, restart.");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to " + normalizedAction + " container " + normalizedName + ": " + e.getMessage(), e);
        }

        try {
            var inspect = dockerClient.inspectContainerCmd(normalizedName).exec();
            var state = inspect.getState();
            var running = state != null && Boolean.TRUE.equals(state.getRunning());
            return new StackContainerView(
                    normalizedName,
                    normalizeState(state == null ? null : state.getStatus(), null),
                    running);
        } catch (RuntimeException e) {
            // The action has already been issued, so return best-effort status.
            return new StackContainerView(normalizedName, "unknown", false);
        }
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
        if (containerName == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: container.");
        }

        var normalized = containerName.trim();
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: container.");
        }
        return normalized;
    }

    private static String normalizeAction(String action) {
        if (action == null || action.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: action.");
        }
        return action.trim().toLowerCase(Locale.ROOT);
    }

    private static String primaryName(String[] names) {
        if (names == null || names.length == 0) {
            return null;
        }
        for (var rawName : names) {
            if (rawName == null || rawName.isBlank()) {
                continue;
            }
            return rawName.startsWith("/") ? rawName.substring(1) : rawName;
        }
        return null;
    }

    private static String normalizeState(String state, String fallbackStatus) {
        if (state != null && !state.isBlank()) {
            return state.trim();
        }
        if (fallbackStatus != null && !fallbackStatus.isBlank()) {
            return fallbackStatus.trim();
        }
        return "unknown";
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
}
