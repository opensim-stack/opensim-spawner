package uk.co.bithatch.opensim.spawner.service;

import static uk.co.bithatch.opensim.jlib.Strings.normalize;
import static uk.co.bithatch.opensim.jlib.Strings.trimLeadingSlash;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
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
import uk.co.bithatch.opensim.jlib.Strings;
import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.ContainerSpec;
import uk.co.bithatch.opensim.spawner.state.GridStateRepository;

@Service
public class DockerJavaService implements DockerService {

    private static final Logger LOG = LoggerFactory.getLogger(DockerJavaService.class);

    private final DockerClient dockerClient;
    private final SpawnerProperties properties;
	private final GridStateRepository gridStateRepository;

    @Autowired
    public DockerJavaService(SpawnerProperties properties, GridStateRepository gridStateRepository) {
        this(properties, buildDockerClient(), gridStateRepository);
    }

    DockerJavaService(SpawnerProperties properties, DockerClient dockerClient, GridStateRepository gridStateRepository) {
        this.dockerClient = dockerClient;
        this.properties = properties;
        this.gridStateRepository = gridStateRepository;
    }

    @Override
    public String resolveLocalDigest(String targetImage) {
        try {
            var inspect = dockerClient.inspectImageCmd(targetImage).exec();
            var repoDigests = inspect == null ? null : inspect.getRepoDigests();
            if (repoDigests == null || repoDigests.isEmpty()) {
                return DIGEST_UNKNOWN;
            }

            var repository = DockerService.dockerHubRepository(targetImage);
            for (var repoDigest : repoDigests) {
                if (repoDigest == null || !repoDigest.contains("@")) {
                    continue;
                }
                var parts = repoDigest.split("@", 2);
                if (parts.length != 2) {
                    continue;
                }
                var digestRepository = DockerService.normalizeDockerIoRepository(parts[0]);
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

    @Override
    public void pullImage(String image) {
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

    @Override
    public void recreateContainer(String containerName, String targetImage, Consumer<ContainerUpdateContext> context) {
        var inspect = inspectContainer(containerName);
        var oldContainerId = inspect.getId();
        var oldContainerName = trimLeadingSlash(inspect.getName());
        var preservedHostConfig = inspect.getHostConfig();
        var preservedEnv = inspect.getConfig().getEnv();
        var preservedAliases = collectNetworkAliases(inspect);

        dockerClient.stopContainerCmd(oldContainerId).exec();
        dockerClient.removeContainerCmd(oldContainerId).withForce(true).exec();

        String createdContainerId = null;
        try {
            var create = dockerClient.createContainerCmd(targetImage)
                    .withName(oldContainerName)
                    .withEnv(preservedEnv)
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
            
            context.accept(new ContainerUpdateContext() {
				@Override
				public void withEnv(String[] env) {
					create.withEnv(env);
				}

				@Override
				public String[] env() {
					return create.getEnv();
				}
            });

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

    @Override
    public List<String> listStackContainers() {
        var projectPrefix = configuredProjectPrefix();
        var containers = dockerClient.listContainersCmd().withShowAll(true).exec();
        var response = new ArrayList<String>();

        for (var container : containers) {
            var containerName = primaryName(container == null ? null : container.getNames());
            if (containerName == null || !containerName.startsWith(projectPrefix) || containerName.matches(".*-init-[0-9]+$")) {
                continue;
            }

            response.add(containerName);
        }

        Collections.sort(response);
        return response;
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

	private String configuredProjectPrefix() {
	    var prefix = properties.getComposeProjectName();
	    if (prefix == null || prefix.isBlank()) {
	        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
	                "COMPOSE_PROJECT_NAME is not configured for stack container discovery.");
	    }
	    return prefix.trim();
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

        var envList= Arrays.asList(Strings.mapToEnvVars(spec.getEnvironment()));
        var createCommand = dockerClient.createContainerCmd(spec.getImage())
                .withName(spec.getName())
                .withHostConfig(hostConfig)
                .withEnv(envList);

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
            dockerClient.restartContainerCmd(id).exec();
            logEffectivePortMappings(id);
            LOG.info("Restarted container {}.", ref);
        }
    }

    private void logEffectivePortMappings(String containerId) {
        try {
            var inspect = inspectContainer(containerId);
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
        }
    }
    
    @Override
    public Map<String, String> getContainerVars(String ref) {
        var idsByName = indexContainerIdsByName();
        var id = resolveContainerId(ref, idsByName);
        if (id == null) {
            throw new IllegalArgumentException("Container {} not found while fetching status. " + ref);
        }
        try {
            var inspect = inspectContainer(id);
            return Strings.envVarsToMap(inspect.getConfig().getEnv());
        } catch (RuntimeException e) {
            LOG.error("Failed to inspect container {} (resolved id={}).", ref, id, e);
            throw new ExternalDependencyException("Failed to inspect Docker container " + ref + ". " + e.getMessage(), e);
        }
    }

    @Override
	public ContainerDetails inspect(String id) {
		var ir = inspectContainer(id);
		return new ContainerDetails(
				ir.getId(),
				ir.getName(),
				ir.getState().getStatus(),
				ir.getState() == null ? false : Boolean.TRUE.equals(ir.getState().getRunning()),
				ir.getConfig().getEnv(),
				ir.getConfig().getImage());
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
                var inspect = inspectContainer(id);
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

	private InspectContainerResponse inspectContainer(String id) {
		return dockerClient.inspectContainerCmd(id).exec();
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
            return inspectContainer(containerRef).getId();
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
    public void shutdownDockerClient() {
        try {
            dockerClient.close();
        } catch (IOException e) {
            LOG.warn("Failed to close Docker client cleanly during shutdown.", e);
        }
    }

    private String resolveContainerDisplayName(String containerId) {
        try {
            var inspect = inspectContainer(containerId);
            var name = inspect.getName();
            if (name != null && !name.isBlank()) {
                return name.replaceFirst("^/", "");
            }
        } catch (RuntimeException e) {
            LOG.debug("Falling back to container ID for display name of {}.", containerId, e);
        }
        return containerId.length() > 12 ? containerId.substring(0, 12) : containerId;
    }

    private static List<String> collectNetworkAliases(
            InspectContainerResponse inspect) {
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
}
