package uk.co.bithatch.opensim.spawner.service;

public record ContainerStatus(
        String containerId,
        String status,
        boolean running,
        String containerName) {
}
