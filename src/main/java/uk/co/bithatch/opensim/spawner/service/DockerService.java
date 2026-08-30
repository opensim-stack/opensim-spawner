package uk.co.bithatch.opensim.spawner.service;

import java.util.Collection;
import java.util.List;

import uk.co.bithatch.opensim.spawner.domain.ContainerSpec;

public interface DockerService {

    List<String> createContainers(Collection<ContainerSpec> specs);

    void startContainers(List<String> containerIds);

    void stopContainers(List<String> containerIds);

    void restartContainers(List<String> containerIds);

    void attachContainerLogs(List<String> containerIds);

    List<ContainerStatus> getContainerStatuses(List<String> containerIds);

    void removeContainers(List<String> containerIds);

    void removeVolumesBySuffix(String suffix);
}
