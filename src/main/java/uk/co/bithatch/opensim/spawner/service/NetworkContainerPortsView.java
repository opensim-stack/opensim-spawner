package uk.co.bithatch.opensim.spawner.service;

import java.util.List;

public record NetworkContainerPortsView(
        String containerName,
        List<NetworkPortView> ports) {
}
