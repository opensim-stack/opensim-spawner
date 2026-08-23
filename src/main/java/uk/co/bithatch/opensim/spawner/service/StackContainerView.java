package uk.co.bithatch.opensim.spawner.service;

public record StackContainerView(
        String containerName,
        String status,
        boolean running) {
}
