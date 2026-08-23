package uk.co.bithatch.opensim.spawner.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.ContainerSpec;

@Service
public class DockerJavaService implements DockerService {

    private static final Logger LOG = LoggerFactory.getLogger(DockerJavaService.class);

    private final DockerClient dockerClient;
    private final SpawnerProperties properties;
    private final Map<String, ResultCallback.Adapter<Frame>> activeLogStreams = new ConcurrentHashMap<>();
    private final Map<String, String> containerDisplayNames = new ConcurrentHashMap<>();

    @Autowired
    public DockerJavaService(SpawnerProperties properties) {
        this(properties, buildDockerClient());
    }

    DockerJavaService(SpawnerProperties properties, DockerClient dockerClient) {
        this.dockerClient = dockerClient;
        this.properties = properties;
    }

    @Override
    public List<String> createContainers(List<ContainerSpec> specs) {
        var ids = new ArrayList<String>();
        for (var spec : specs) {
            LOG.info("Creating container {} from image {}.", spec.getName(), spec.getImage());

            ensureImageByPullPolicy(spec.getImage());

            var hostConfig = HostConfig.newHostConfig();
            var binds = toBinds(spec.getVolumes());
            if (!binds.isEmpty()) {
                hostConfig = HostConfig.newHostConfig().withBinds(binds);
            }
            hostConfig = hostConfig.withRestartPolicy(RestartPolicy.parse(properties.getOpensimRestartPolicy()));
            var configuredNetwork = normalizeNetworkName(properties.getOpensimNetwork());
            if (configuredNetwork != null) {
                hostConfig = hostConfig.withNetworkMode(configuredNetwork);
            }

            var createCommand = dockerClient.createContainerCmd(spec.getImage())
                    .withName(spec.getName())
                    .withHostConfig(hostConfig)
                    .withEnv(toEnvList(spec.getEnvironment()));

            CreateContainerResponse response = createCommand.exec();
            ids.add(response.getId());
            LOG.info("Created container {} with id {}.", spec.getName(), response.getId());
        }
        return ids;
    }

    @Override
    public void startContainers(List<String> containerIds) {
        for (var id : containerIds) {
            LOG.info("Starting container {}.", id);
            dockerClient.startContainerCmd(id).exec();
            attachLogStreaming(id);
            LOG.info("Started container {}.", id);
        }
    }

    @Override
    public void stopContainers(List<String> containerIds) {
        for (var id : containerIds) {
            LOG.info("Stopping container {}.", id);
            detachLogStreaming(id);
            dockerClient.stopContainerCmd(id).exec();
            LOG.info("Stopped container {}.", id);
        }
    }

    @Override
    public void restartContainers(List<String> containerIds) {
        for (var id : containerIds) {
            LOG.info("Restarting container {}.", id);
            detachLogStreaming(id);
            dockerClient.restartContainerCmd(id).exec();
            attachLogStreaming(id);
            LOG.info("Restarted container {}.", id);
        }
    }

    @Override
    public void attachContainerLogs(List<String> containerIds) {
        for (var id : containerIds) {
            attachLogStreaming(id);
        }
    }

    @Override
    public List<ContainerStatus> getContainerStatuses(List<String> containerIds) {
        var statuses = new ArrayList<ContainerStatus>();
        for (var id : containerIds) {
            try {
                var inspect = dockerClient.inspectContainerCmd(id).exec();
                var state = inspect.getState();
                var name = inspect.getName() == null ? "" : inspect.getName().replaceFirst("^/", "");
                LOG.info("Container status {} ({}): {}.", name, id, state == null ? "unknown" : String.valueOf(state.getStatus()));
                statuses.add(new ContainerStatus(
                        id,
                        state == null ? "unknown" : String.valueOf(state.getStatus()),
                        state != null && Boolean.TRUE.equals(state.getRunning()),
                        name));
            } catch (NotFoundException e) {
                LOG.warn("Container {} not found while fetching status.", id);
                statuses.add(new ContainerStatus(id, "missing", false, ""));
            } catch (RuntimeException e) {
                LOG.error("Failed to inspect container {}.", id, e);
                throw new ExternalDependencyException("Failed to inspect Docker container " + id + ". " + e.getMessage(), e);
            }
        }
        return statuses;
    }

    @Override
    public void removeContainers(List<String> containerIds) {
        for (var id : containerIds) {
            try {
                LOG.info("Removing container {} (force=true, removeVolumes=true).", id);
                detachLogStreaming(id);
                dockerClient.removeContainerCmd(id).withForce(true).withRemoveVolumes(true).exec();
                LOG.info("Removed container {}.", id);
            } catch (NotFoundException ignored) {
                LOG.warn("Container {} already removed.", id);
                // Rollback is best effort; already removed containers are acceptable.
            } catch (RuntimeException e) {
                LOG.error("Failed to remove container {}.", id, e);
                throw e;
            }
        }
    }

    @Override
    public void removeVolumesBySuffix(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return;
        }

        var normalizedSuffix = suffix.trim();
        var listed = dockerClient.listVolumesCmd().exec();
        var volumes = listed == null ? null : listed.getVolumes();
        if (volumes == null || volumes.isEmpty()) {
            return;
        }

        for (var volume : volumes) {
            var name = volume == null ? null : volume.getName();
            if (name == null || !name.endsWith(normalizedSuffix)) {
                continue;
            }

            try {
                LOG.info("Removing named volume {} for suffix {}.", name, normalizedSuffix);
                dockerClient.removeVolumeCmd(name).exec();
                LOG.info("Removed named volume {}.", name);
            } catch (NotFoundException ignored) {
                LOG.warn("Named volume {} already removed.", name);
            } catch (RuntimeException e) {
                LOG.error("Failed to remove named volume {}.", name, e);
                throw new ExternalDependencyException("Failed to remove Docker volume " + name + ". " + e.getMessage(), e);
            }
        }
    }

    private void ensureImageByPullPolicy(String image) {
        var policy = normalizePullPolicy(properties.getOpensimPullPolicy());
        switch (policy) {
            case "always" -> pullImage(image);
            case "ifnotpresent" -> {
                if (!imageExistsLocally(image)) {
                    pullImage(image);
                }
            }
            case "never" -> {
                if (!imageExistsLocally(image)) {
                    throw new ExternalDependencyException(
                            "Image " + image + " not found locally and pull policy is Never.");
                }
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported pull policy '" + properties.getOpensimPullPolicy()
                            + "'. Supported values: Always, IfNotPresent, Never.");
        }
    }

    private static String normalizePullPolicy(String policy) {
        if (policy == null || policy.isBlank() || policy.equalsIgnoreCase("missing")) {
            return "ifnotpresent";
        }
        return policy.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    private boolean imageExistsLocally(String image) {
        try {
            dockerClient.inspectImageCmd(image).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }

    private void pullImage(String image) {
        try {
            LOG.info("Pulling image {} due to pull policy {}.", image, properties.getOpensimPullPolicy());
            dockerClient.pullImageCmd(image).start().awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalDependencyException("Interrupted while pulling Docker image " + image + ".", e);
        } catch (RuntimeException e) {
            throw new ExternalDependencyException("Failed to pull Docker image " + image + ". " + e.getMessage(), e);
        }
    }

    private static List<String> toEnvList(Map<String, String> env) {
        var values = new ArrayList<String>();
        for (var entry : env.entrySet()) {
            values.add(entry.getKey() + "=" + entry.getValue());
        }
        return values;
    }

    private static List<Bind> toBinds(Map<String, String> volumes) {
        var binds = new ArrayList<Bind>();
        for (var entry : volumes.entrySet()) {
            binds.add(new Bind(entry.getKey(), new Volume(entry.getValue())));
        }
        return binds;
    }

    private static String normalizeNetworkName(String networkName) {
        if (networkName == null) {
            return null;
        }
        var trimmed = networkName.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
    public void shutdownLoggingAndDockerClient() {
        for (var containerId : List.copyOf(activeLogStreams.keySet())) {
            detachLogStreaming(containerId);
        }
        try {
            dockerClient.close();
        } catch (IOException e) {
            LOG.warn("Failed to close Docker client cleanly during shutdown.", e);
        }
    }

    private void attachLogStreaming(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            return;
        }
        if (activeLogStreams.containsKey(containerId)) {
            return;
        }

        var displayName = resolveContainerDisplayName(containerId);
        containerDisplayNames.put(containerId, displayName);

        var callback = new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                var payload = frame == null ? null : frame.getPayload();
                if (payload == null || payload.length == 0) {
                    return;
                }
                var message = new String(payload, StandardCharsets.UTF_8).stripTrailing();
                if (message.isEmpty()) {
                    return;
                }
                for (var line : message.split("\\R")) {
                    if (!line.isBlank()) {
                        System.err.println("[container:" + displayName + "] " + line);
                    }
                }
            }

            @Override
            public void onError(Throwable throwable) {
                LOG.warn("Container log stream for {} ({}) ended with error: {}",
                        displayName,
                        containerId,
                        throwable.getMessage());
            }
        };

        if (activeLogStreams.putIfAbsent(containerId, callback) != null) {
            return;
        }

        try {
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .exec(callback);
            LOG.info("Attached log stream for container {} ({}).", displayName, containerId);
        } catch (RuntimeException e) {
            activeLogStreams.remove(containerId);
            containerDisplayNames.remove(containerId);
            LOG.warn("Could not attach log stream for container {}.", containerId, e);
        }
    }

    private void detachLogStreaming(String containerId) {
        var displayName = containerDisplayNames.remove(containerId);
        var callback = activeLogStreams.remove(containerId);
        if (callback == null) {
            return;
        }
        try {
            callback.close();
            if (displayName == null) {
                LOG.info("Detached log stream for container {}.", containerId);
            } else {
                LOG.info("Detached log stream for container {} ({}).", displayName, containerId);
            }
        } catch (IOException e) {
            LOG.warn("Failed to detach log stream for container {} cleanly.", containerId, e);
        }
    }

    private String resolveContainerDisplayName(String containerId) {
        try {
            var inspect = dockerClient.inspectContainerCmd(containerId).exec();
            var name = inspect.getName();
            if (name != null && !name.isBlank()) {
                return name.replaceFirst("^/", "");
            }
        } catch (RuntimeException e) {
            LOG.debug("Falling back to container ID for display name of {}.", containerId, e);
        }
        return containerId.length() > 12 ? containerId.substring(0, 12) : containerId;
    }
}
