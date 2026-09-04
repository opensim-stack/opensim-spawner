package uk.co.bithatch.opensim.spawner.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.InternetProtocol;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

import jakarta.annotation.PreDestroy;
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
    public List<String> createContainers(Collection<ContainerSpec> specs) {
        var containerRefs = new ArrayList<String>();
        for (var spec : specs) {
            LOG.info("Creating container {}.", spec);

            runInitContainers(spec);
            CreateContainerResponse response = createContainerCommand(spec, properties.getOpensimRestartPolicy(), null).exec();
            var stableReference = spec.getName() == null || spec.getName().isBlank() ? response.getId() : spec.getName();
            containerRefs.add(stableReference);
            LOG.info("Created container {} with id {}.", spec.getName(), response.getId());
        }
        return containerRefs;
    }

    private void runInitContainers(ContainerSpec parentSpec) {
        var initSpecs = parentSpec.getInit();
        if (initSpecs == null || initSpecs.isEmpty()) {
            return;
        }

        for (var initEntry : initSpecs.entrySet()) {
            var configuredInit = initEntry.getValue();
            if (configuredInit == null) {
                throw new IllegalArgumentException("Init container specification is missing for image key '" + initEntry.getKey() + "'.");
            }
            if (configuredInit.getInit() != null && !configuredInit.getInit().isEmpty()) {
                throw new IllegalArgumentException("Nested init containers are not supported.");
            }

            var image = configuredInit.getImage() == null || configuredInit.getImage().isBlank()
                    ? initEntry.getKey()
                    : configuredInit.getImage();
            if (image == null || image.isBlank()) {
                throw new IllegalArgumentException("Init container image is missing for parent '" + parentSpec.getName() + "'.");
            }

            var initName = configuredInit.getName();
            if (initName == null || initName.isBlank()) {
                if (parentSpec.getName() == null || parentSpec.getName().isBlank()) {
                    throw new IllegalArgumentException("Init container requires a name when parent container name is blank.");
                }
                initName = parentSpec.getName() + "-init";
                configuredInit.setName(initName);
            }
            configuredInit.setImage(image);

            LOG.info("Running init container '{}' for parent '{}'.", initName, parentSpec.getName());
            removeExistingContainerByName(initName);

            var response = createContainerCommand(configuredInit, "no", List.of("/bin/sh", "/init.sh")).exec();
            var initContainerId = response.getId();

            try {
                dockerClient.startContainerCmd(initContainerId).exec();
                var statusCode = dockerClient.waitContainerCmd(initContainerId).start().awaitStatusCode();
                if (statusCode == null || statusCode.intValue() != 0) {
                    throw new ExternalDependencyException(
                            "Init container '" + initName + "' failed for parent '" + parentSpec.getName() + "' with status "
                                    + statusCode + ".");
                }
                LOG.info("Init container '{}' completed successfully (service_completed condition met).", initName);
            } finally {
                try {
                    dockerClient.removeContainerCmd(initContainerId).withForce(true).exec();
                } catch (NotFoundException ignored) {
                    LOG.debug("Init container {} already removed.", initContainerId);
                }
            }
        }
    }

    private CreateContainerCmd createContainerCommand(ContainerSpec spec, String restartPolicy, List<String> entrypoint) {
        ensureImageByPullPolicy(spec.getImage());

        var hostConfig = HostConfig.newHostConfig();
        var binds = toBinds(spec.getVolumes());
        if (!binds.isEmpty()) {
            hostConfig.withBinds(binds);
        }
        hostConfig.withRestartPolicy(RestartPolicy.parse(restartPolicy));

        var configuredNetwork = normalizeNetworkName(properties.getOpensimNetwork());
        if (configuredNetwork != null) {
            hostConfig.withNetworkMode(configuredNetwork);
        }

        spec.getExtraHosts().forEach((host, ip) -> hostConfig.withExtraHosts(host + ":" + ip));
        var portBindings = toPortBindings(spec.getPorts(), spec.getName());
        if (!portBindings.isEmpty()) {
            hostConfig.withPortBindings(portBindings);
        }

        var createCommand = dockerClient.createContainerCmd(spec.getImage())
                .withName(spec.getName())
                .withHostConfig(hostConfig)
                .withEnv(toEnvList(spec.getEnvironment()));

        var exposedPorts = toExposedPorts(portBindings);
        if (!exposedPorts.isEmpty()) {
            createCommand.withExposedPorts(exposedPorts);
        }

        if (entrypoint != null && !entrypoint.isEmpty()) {
            createCommand.withEntrypoint(entrypoint);
        }

        if (spec.getHostname() != null && !spec.getHostname().isBlank()) {
            createCommand.withHostName(spec.getHostname());
        }
        createCommand.withAliases(spec.getAliases());

        var hcheck = spec.getHealthCheck();
        if (hcheck != null) {
            var nhcheck = new com.github.dockerjava.api.model.HealthCheck();
            nhcheck.withTest(hcheck.test());
            nhcheck.withInterval(Duration.ofSeconds(hcheck.interval()).toNanos());
            nhcheck.withRetries(hcheck.retries());
            nhcheck.withStartPeriod(Duration.ofSeconds(hcheck.startPeriod()).toNanos());
            nhcheck.withTimeout(Duration.ofSeconds(hcheck.timeout()).toNanos());
            createCommand.withHealthcheck(nhcheck);
        }

        return createCommand;
    }

    private void removeExistingContainerByName(String name) {
        var expectedName = "/" + name;
        var existing = dockerClient.listContainersCmd().withShowAll(true).exec().stream()
                .filter(container -> container.getNames() != null
                        && Arrays.stream(container.getNames()).anyMatch(expectedName::equals))
                .toList();

        for (var container : existing) {
            var id = container.getId();
            try {
                dockerClient.removeContainerCmd(id).withForce(true).exec();
                LOG.info("Removed stale container '{}' ({}) before init run.", name, id);
            } catch (NotFoundException ignored) {
                LOG.debug("Stale container '{}' ({}) already removed.", name, id);
            }
        }
    }

    @Override
    public void startContainers(List<String> containerRefs) {
        var idsByName = indexContainerIdsByName();
        for (var ref : containerRefs) {
            var id = resolveContainerId(ref, idsByName);
            if (id == null) {
                throw new NotFoundException("Container not found: " + ref);
            }
            LOG.info("Starting container {} (resolved id={}).", ref, id);
            dockerClient.startContainerCmd(id).exec();
            logEffectivePortMappings(id);
            attachLogStreaming(id);
            LOG.info("Started container {}.", ref);
        }
    }

    @Override
    public void stopContainers(List<String> containerRefs) {
        var idsByName = indexContainerIdsByName();
        for (var ref : containerRefs) {
            var id = resolveContainerId(ref, idsByName);
            if (id == null) {
                throw new NotFoundException("Container not found: " + ref);
            }
            LOG.info("Stopping container {} (resolved id={}).", ref, id);
            detachLogStreaming(id);
            dockerClient.stopContainerCmd(id).exec();
            LOG.info("Stopped container {}.", ref);
        }
    }

    @Override
    public void restartContainers(List<String> containerRefs) {
        var idsByName = indexContainerIdsByName();
        for (var ref : containerRefs) {
            var id = resolveContainerId(ref, idsByName);
            if (id == null) {
                throw new NotFoundException("Container not found: " + ref);
            }
            LOG.info("Restarting container {} (resolved id={}).", ref, id);
            detachLogStreaming(id);
            dockerClient.restartContainerCmd(id).exec();
            logEffectivePortMappings(id);
            attachLogStreaming(id);
            LOG.info("Restarted container {}.", ref);
        }
    }

    private void logEffectivePortMappings(String containerId) {
        try {
            var inspect = dockerClient.inspectContainerCmd(containerId).exec();
            var displayName = inspect.getName() == null ? resolveContainerDisplayName(containerId)
                    : inspect.getName().replaceFirst("^/", "");
            var hostConfigPorts = inspect.getHostConfig() == null ? null : inspect.getHostConfig().getPortBindings();
            var networkPorts = inspect.getNetworkSettings() == null ? null : inspect.getNetworkSettings().getPorts();

            LOG.info("Container {} ({}) effective Docker port mappings - HostConfig.PortBindings: {}; NetworkSettings.Ports: {}.",
                    displayName,
                    containerId,
                    formatDockerPorts(hostConfigPorts),
                    formatDockerPorts(networkPorts));
        } catch (RuntimeException e) {
            LOG.warn("Unable to inspect effective Docker port mappings for container {}.", containerId, e);
        }
    }

    private static String formatDockerPorts(Ports ports) {
        if (ports == null || ports.getBindings() == null || ports.getBindings().isEmpty()) {
            return "<none>";
        }

        return ports.getBindings().entrySet().stream()
                .sorted((left, right) -> left.getKey().toString().compareTo(right.getKey().toString()))
                .map(entry -> {
                    var bindings = entry.getValue();
                    if (bindings == null || bindings.length == 0) {
                        return entry.getKey() + "=[]";
                    }
                    var rendered = Arrays.stream(bindings)
                            .map(DockerJavaService::formatPortBinding)
                            .collect(Collectors.joining(","));
                    return entry.getKey() + "=[" + rendered + "]";
                })
                .collect(Collectors.joining("; "));
    }

    private static String formatPortBinding(Ports.Binding binding) {
        if (binding == null) {
            return "<null>";
        }
        var hostIp = binding.getHostIp();
        var hostPort = binding.getHostPortSpec();
        if (hostPort == null || hostPort.isBlank()) {
            hostPort = "<none>";
        }
        if (hostIp == null || hostIp.isBlank()) {
            return hostPort;
        }
        return hostIp + ":" + hostPort;
    }

    @Override
    public void attachContainerLogs(List<String> containerRefs) {
        var idsByName = indexContainerIdsByName();
        for (var ref : containerRefs) {
            var id = resolveContainerId(ref, idsByName);
            if (id == null) {
                LOG.warn("Container {} not found while attaching logs.", ref);
                continue;
            }
            attachLogStreaming(id);
        }
    }

    @Override
    public List<ContainerStatus> getContainerStatuses(List<String> containerRefs) {
        var statuses = new ArrayList<ContainerStatus>();
        var idsByName = indexContainerIdsByName();
        for (var ref : containerRefs) {
            var id = resolveContainerId(ref, idsByName);
            if (id == null) {
                LOG.warn("Container {} not found while fetching status.", ref);
                statuses.add(new ContainerStatus(ref, "missing", false, ""));
                continue;
            }
            try {
                var inspect = dockerClient.inspectContainerCmd(id).exec();
                var state = inspect.getState();
                var name = inspect.getName() == null ? "" : inspect.getName().replaceFirst("^/", "");
                LOG.info("Container status {} (ref={}, id={}): {}.",
                        name,
                        ref,
                        id,
                        state == null ? "unknown" : String.valueOf(state.getStatus()));
                statuses.add(new ContainerStatus(
                        ref,
                        state == null ? "unknown" : String.valueOf(state.getStatus()),
                        state != null && Boolean.TRUE.equals(state.getRunning()),
                        name));
            } catch (NotFoundException e) {
                LOG.warn("Container {} (resolved id={}) not found while fetching status.", ref, id);
                statuses.add(new ContainerStatus(ref, "missing", false, ""));
            } catch (RuntimeException e) {
                LOG.error("Failed to inspect container {} (resolved id={}).", ref, id, e);
                throw new ExternalDependencyException("Failed to inspect Docker container " + ref + ". " + e.getMessage(), e);
            }
        }
        return statuses;
    }

    @Override
    public void removeContainers(List<String> containerRefs) {
        var idsByName = indexContainerIdsByName();
        for (var ref : containerRefs) {
            var id = resolveContainerId(ref, idsByName);
            if (id == null) {
                LOG.warn("Container {} already removed.", ref);
                continue;
            }
            try {
                LOG.info("Removing container {} (resolved id={}, force=true, removeVolumes=true).", ref, id);
                detachLogStreaming(id);
                dockerClient.removeContainerCmd(id).withForce(true).withRemoveVolumes(true).exec();
                LOG.info("Removed container {}.", ref);
            } catch (NotFoundException ignored) {
                LOG.warn("Container {} already removed.", ref);
                // Rollback is best effort; already removed containers are acceptable.
            } catch (RuntimeException e) {
                LOG.error("Failed to remove container {} (resolved id={}).", ref, id, e);
                throw e;
            }
        }
    }

    private Map<String, String> indexContainerIdsByName() {
        var idsByName = new LinkedHashMap<String, String>();
        for (var container : dockerClient.listContainersCmd().withShowAll(true).exec()) {
            var id = container == null ? null : container.getId();
            var names = container == null ? null : container.getNames();
            if (id == null || names == null) {
                continue;
            }
            for (var rawName : names) {
                var name = normalizeContainerName(rawName);
                if (name != null) {
                    idsByName.put(name, id);
                }
            }
        }
        return idsByName;
    }

    private String resolveContainerId(String containerRef, Map<String, String> idsByName) {
        if (containerRef == null || containerRef.isBlank()) {
            return null;
        }

        try {
            return dockerClient.inspectContainerCmd(containerRef).exec().getId();
        } catch (NotFoundException ignored) {
            var normalized = normalizeContainerName(containerRef);
            return normalized == null ? null : idsByName.get(normalized);
        }
    }

    private static String normalizeContainerName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        return rawName.startsWith("/") ? rawName.substring(1) : rawName;
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

    private static List<PortBinding> toPortBindings(Map<String, String> ports, String containerName) {
        if (ports == null || ports.isEmpty()) {
            LOG.info("Container {} has no configured port mappings.", displayContainerName(containerName));
            return List.of();
        }

        LOG.info("Container {} requested {} port mapping definition(s): {}",
                displayContainerName(containerName),
                ports.size(),
                ports);

        var bindings = new ArrayList<PortBinding>();
        for (var entry : ports.entrySet()) {
            var containerSpec = entry.getKey();
            var hostSpec = entry.getValue();

            var expanded = expandPortBinding(containerSpec, hostSpec);
            bindings.addAll(expanded);

            logPortExpansion(containerName, containerSpec, hostSpec, expanded.size());
        }

        LOG.info("Container {} resolved {} concrete Docker port binding(s): {}",
                displayContainerName(containerName),
                bindings.size(),
                bindings.stream().map(String::valueOf).collect(Collectors.joining(", ")));

        if (LOG.isDebugEnabled()) {
            // Debug dump to aid diagnosis when only the start of a range appears to bind remotely.
            for (var binding : bindings) {
                LOG.debug("Container {} concrete binding: {}",
                        displayContainerName(containerName),
                        String.valueOf(binding));
            }
        }

        return bindings;
    }

    private static List<ExposedPort> toExposedPorts(List<PortBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        var exposed = new ArrayList<ExposedPort>();
        for (var binding : bindings) {
            if (binding == null || binding.getExposedPort() == null) {
                continue;
            }
            var port = binding.getExposedPort();
            if (!exposed.contains(port)) {
                exposed.add(port);
            }
        }
        return exposed;
    }

    private static void logPortExpansion(String containerName, String containerSpec, String hostSpec, int expandedCount) {
        var containerRange = parsePortRange(containerSpec, true);
        var hostRange = parsePortRange(hostSpec, false);
        if (containerRange.isRange() && hostRange.isRange()) {
            LOG.info("Container {} range mapping {}-{}{} -> {}-{} expanded to {} binding(s).",
                    displayContainerName(containerName),
                    containerRange.start(),
                    containerRange.end(),
                    "/" + containerRange.protocol().name().toLowerCase(Locale.ROOT),
                    hostRange.start(),
                    hostRange.end(),
                    expandedCount);
        } else {
            LOG.info("Container {} single port mapping {} -> {} expanded to {} binding(s).",
                    displayContainerName(containerName),
                    containerSpec,
                    hostSpec,
                    expandedCount);
        }
    }

    private static String displayContainerName(String containerName) {
        return containerName == null || containerName.isBlank() ? "<unnamed>" : containerName;
    }

    private static List<PortBinding> expandPortBinding(String containerSpec, String hostSpec) {
        var containerRange = parsePortRange(containerSpec, true);
        var hostRange = parsePortRange(hostSpec, false);

        if (!containerRange.isRange() && !hostRange.isRange()) {
            return List.of(new PortBinding(Ports.Binding.bindPortSpec(hostSpec),
                    new ExposedPort(containerRange.start(), containerRange.protocol())));
        }

        if (!containerRange.isRange() || !hostRange.isRange()) {
            throw new IllegalArgumentException(
                    "Port ranges must map range-to-range. Got container='" + containerSpec + "', host='" + hostSpec + "'.");
        }

        var containerSize = containerRange.end() - containerRange.start();
        var hostSize = hostRange.end() - hostRange.start();
        if (containerSize != hostSize) {
            throw new IllegalArgumentException(
                    "Port range sizes must match. Got container='" + containerSpec + "', host='" + hostSpec + "'.");
        }

        var expanded = new ArrayList<PortBinding>();
        for (int offset = 0; offset <= containerSize; offset++) {
            expanded.add(new PortBinding(
                    Ports.Binding.bindPort(hostRange.start() + offset),
                    new ExposedPort(containerRange.start() + offset, containerRange.protocol())));
        }
        return expanded;
    }

    private static ParsedPortRange parsePortRange(String spec, boolean withProtocol) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("Port specification must not be blank.");
        }

        var raw = spec.trim();
        var protocol = InternetProtocol.TCP;
        var portSegment = raw;
        if (raw.contains("/")) {
            var split = raw.split("/", 2);
            portSegment = split[0].trim();
            var protocolToken = split.length > 1 ? split[1].trim() : "tcp";
            protocol = parseInternetProtocol(protocolToken);
        } else if (withProtocol) {
            // Keep parity with previous expectations for ExposedPort-like values.
            protocol = InternetProtocol.TCP;
        }

        if (portSegment.contains(":")) {
            throw new IllegalArgumentException(
                    "Port ranges with host IP binding are not supported in this format: '" + spec + "'.");
        }

        if (portSegment.contains("-")) {
            var parts = portSegment.split("-", 2);
            var start = parsePortNumber(parts[0], spec);
            var end = parsePortNumber(parts[1], spec);
            if (end < start) {
                throw new IllegalArgumentException("Invalid port range '" + spec + "': end is less than start.");
            }
            return new ParsedPortRange(start, end, protocol, true);
        }

        var port = parsePortNumber(portSegment, spec);
        return new ParsedPortRange(port, port, protocol, false);
    }

    private static int parsePortNumber(String token, String originalSpec) {
        try {
            var port = Integer.parseInt(token.trim());
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Port out of range in specification '" + originalSpec + "'.");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port number in specification '" + originalSpec + "'.", e);
        }
    }

    private static InternetProtocol parseInternetProtocol(String protocolToken) {
        if (protocolToken == null || protocolToken.isBlank()) {
            return InternetProtocol.TCP;
        }
        return switch (protocolToken.toLowerCase(Locale.ROOT)) {
            case "tcp" -> InternetProtocol.TCP;
            case "udp" -> InternetProtocol.UDP;
            case "sctp" -> InternetProtocol.SCTP;
            default -> throw new IllegalArgumentException("Unsupported port protocol '" + protocolToken + "'.");
        };
    }

    private record ParsedPortRange(int start, int end, InternetProtocol protocol, boolean isRange) {
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
