package uk.co.bithatch.opensim.spawner.service;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

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
    private final UpdateService updateService;

    @Autowired
    public StackContainerService(SpawnerProperties properties, UpdateService updateService) {
        this(properties, buildDockerClient(), updateService);
    }

    StackContainerService(SpawnerProperties properties, DockerClient dockerClient, UpdateService updateService) {
        this.properties = properties;
        this.dockerClient = dockerClient;
        this.updateService = updateService;
    }

    public List<StackContainerView> listStackContainers() {
        var projectPrefix = configuredProjectPrefix();
        var containers = dockerClient.listContainersCmd().withShowAll(true).exec();
        var response = new ArrayList<StackContainerView>();
        var updateStatus = updateService == null
                ? java.util.Map.<String, UpdateService.StackContainerUpdateStatus>of()
                : updateService.containerUpdateStatus(false);

        for (var container : containers) {
            var containerName = primaryName(container == null ? null : container.getNames());
            if (containerName == null || !containerName.startsWith(projectPrefix) || containerName.matches(".*-init-[0-9]+$")) {
                continue;
            }

            var state = normalizeState(container == null ? null : container.getState(),
                    container == null ? null : container.getStatus());
            var updateAvailable = updateStatus.get(containerName) != null
                    && updateStatus.get(containerName).updateAvailable();
            response.add(new StackContainerView(
                    containerName,
                    state,
                    "running".equalsIgnoreCase(container == null ? null : container.getState()),
                    updateAvailable));
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
                case "update" -> {
                    if (updateService == null) {
                        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                                "Update service is not available.");
                    }
                    updateService.updateContainer(normalizedName);
                }
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unsupported action '" + action + "'. Supported actions: start, stop, restart, update.");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to " + normalizedAction + " container " + normalizedName + ": " + e.getMessage(), e);
        }

        try {
            return inspectView(normalizedName);
        } catch (RuntimeException e) {
            // The action has already been issued, so return best-effort status.
            return new StackContainerView(normalizedName, "unknown", false, false);
        }
    }

    public List<StackContainerView> updateAllSequentially() {
        if (updateService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Update service is not available.");
        }
        var updated = updateService.updateAllSequentially();
        var views = new ArrayList<StackContainerView>();
        for (var status : updated) {
            views.add(inspectView(status.containerName()));
        }
        views.sort(Comparator.comparing(StackContainerView::containerName));
        return views;
    }

    private StackContainerView inspectView(String containerName) {
        var inspect = dockerClient.inspectContainerCmd(containerName).exec();
        var state = inspect.getState();
        var running = state != null && Boolean.TRUE.equals(state.getRunning());
        var updates = updateService == null ? java.util.Map.<String, UpdateService.StackContainerUpdateStatus>of()
                : updateService.containerUpdateStatus(true);
        return new StackContainerView(
                containerName,
                normalizeState(state == null ? null : state.getStatus(), null),
                running,
                updates.containsKey(containerName) && updates.get(containerName).updateAvailable());
    }

    public List<NetworkContainerPortsView> listNetworkContainerPorts() {
        var projectPrefix = configuredProjectPrefix();
        var containers = dockerClient.listContainersCmd().exec();
        var response = new ArrayList<NetworkContainerPortsView>();

        for (var container : containers) {
            var containerName = primaryName(container == null ? null : container.getNames());
            if (containerName == null || !containerName.startsWith(projectPrefix) || containerName.matches(".*-init-[0-9]+$")) {
                continue;
            }

            var byProtocol = new LinkedHashMap<String, Set<Integer>>();
            var ports = container == null ? null : container.getPorts();
            if (ports == null || ports.length == 0) {
                continue;
            }

            for (var port : ports) {
                if (port == null || port.getPublicPort() == null || port.getPublicPort() < 1) {
                    continue;
                }

                var protocol = normalizeProtocol(port.getType());
                byProtocol.computeIfAbsent(protocol, _ignored -> new TreeSet<>()).add(port.getPublicPort());
            }

            if (byProtocol.isEmpty()) {
                continue;
            }

            var mappedPorts = new ArrayList<NetworkPortView>();
            for (var entry : byProtocol.entrySet()) {
                for (var value : entry.getValue()) {
                    mappedPorts.add(new NetworkPortView(value, entry.getKey()));
                }
            }

            response.add(new NetworkContainerPortsView(containerName, List.copyOf(mappedPorts)));
        }

        response.sort(Comparator.comparing(NetworkContainerPortsView::containerName));
        return response;
    }

    public NetworkAddressStatusView detectNetworkAddressStatus() {
        var address = findBestLanAddress();
        if (address == null) {
            var loopback = InetAddress.getLoopbackAddress();
            return new NetworkAddressStatusView(loopback.getHostAddress(), "LOCALHOST");
        }
        return new NetworkAddressStatusView(address.getHostAddress(), "LAN");
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

    private static String normalizeProtocol(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return "tcp";
        }
        return protocol.trim().toLowerCase(Locale.ROOT);
    }

    private static InetAddress findBestLanAddress() {
        InetAddress firstNonLoopback = null;
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                var networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }

                var addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    var address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                        continue;
                    }
                    if (address.isSiteLocalAddress()) {
                        return address;
                    }
                    if (firstNonLoopback == null) {
                        firstNonLoopback = address;
                    }
                }
            }
        } catch (SocketException ignored) {
            // Fall back to localhost when adapter discovery is unavailable.
        }
        return firstNonLoopback;
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
