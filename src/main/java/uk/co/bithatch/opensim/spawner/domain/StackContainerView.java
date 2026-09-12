package uk.co.bithatch.opensim.spawner.domain;

public record StackContainerView(
        String containerName,
        String status,
        String image,
        boolean running,
        boolean updateAvailable) {
}
