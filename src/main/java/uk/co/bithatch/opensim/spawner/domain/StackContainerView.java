package uk.co.bithatch.opensim.spawner.domain;

public record StackContainerView(
        String containerName,
        String status,
        boolean running,
        boolean updateAvailable) {
}
