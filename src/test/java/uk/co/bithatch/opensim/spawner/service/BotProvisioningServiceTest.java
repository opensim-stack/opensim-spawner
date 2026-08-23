package uk.co.bithatch.opensim.spawner.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.BotInstanceData;
import uk.co.bithatch.opensim.spawner.state.BotStateRepository;

class BotProvisioningServiceTest {

    private static final class RecordingDockerService implements DockerService {
        private List<String> started = List.of();
        private List<String> stopped = List.of();
        private List<String> restarted = List.of();
        private List<String> attachedLogs = List.of();
        private List<String> removed = List.of();
        private String removedVolumeSuffix;
        private final Map<String, ContainerStatus> statusesByContainerId = new LinkedHashMap<>();

        @Override
        public List<String> createContainers(List<uk.co.bithatch.opensim.spawner.domain.ContainerSpec> specs) {
            return List.of();
        }

        @Override
        public void startContainers(List<String> containerIds) {
            started = new ArrayList<>(containerIds);
        }

        @Override
        public void stopContainers(List<String> containerIds) {
            stopped = new ArrayList<>(containerIds);
        }

        @Override
        public void restartContainers(List<String> containerIds) {
            restarted = new ArrayList<>(containerIds);
        }

        @Override
        public void attachContainerLogs(List<String> containerIds) {
            attachedLogs = new ArrayList<>(containerIds);
        }

        @Override
        public List<ContainerStatus> getContainerStatuses(List<String> containerIds) {
            var statuses = new ArrayList<ContainerStatus>();
            for (var containerId : containerIds) {
                statuses.add(statusesByContainerId.getOrDefault(
                        containerId,
                        new ContainerStatus(containerId, "exited", false, containerId)));
            }
            return statuses;
        }

        @Override
        public void removeContainers(List<String> containerIds) {
            removed = new ArrayList<>(containerIds);
        }

        @Override
        public void removeVolumesBySuffix(String suffix) {
            removedVolumeSuffix = suffix;
        }

        void setStatus(ContainerStatus status) {
            statusesByContainerId.put(status.containerId(), status);
        }
    }

    @TempDir
    Path tempDir;

    @Test
    void restartBotRestartsAllKnownContainers() {
        var props = new SpawnerProperties();
        props.setDataDir(tempDir);
        props.setOpensimCreateBotUser(false);

        var repo = new BotStateRepository(new ObjectMapper(), props);
        var bot = new BotInstanceData();
        bot.setFirst("Ada");
        bot.setLast("Actor");
        bot.setContainerIds(List.of("container-1", "container-2"));
        repo.save(bot);

        var dockerService = new RecordingDockerService();
        var service = new BotProvisioningService(
                repo,
                null,
                null,
                dockerService,
                null,
                null,
                props,
                new Appearances());

        service.restartBot("Ada", "Actor");

        assertEquals(List.of("container-1", "container-2"), dockerService.restarted);
    }

    @Test
    void startBotStartsAllKnownContainers() {
        var props = new SpawnerProperties();
        props.setDataDir(tempDir);
        props.setOpensimCreateBotUser(false);

        var repo = new BotStateRepository(new ObjectMapper(), props);
        var bot = new BotInstanceData();
        bot.setFirst("Ada");
        bot.setLast("Actor");
        bot.setContainerIds(List.of("container-1", "container-2"));
        repo.save(bot);

        var dockerService = new RecordingDockerService();
        var service = new BotProvisioningService(
                repo,
                null,
                null,
                dockerService,
                null,
                null,
                props,
                new Appearances());

        service.startBot("Ada", "Actor");

        assertEquals(List.of("container-1", "container-2"), dockerService.started);
    }

    @Test
    void startupReconnectStartsKnownStoppedContainers() {
        var props = new SpawnerProperties();
        props.setDataDir(tempDir);
        props.setOpensimCreateBotUser(false);

        var repo = new BotStateRepository(new ObjectMapper(), props);
        var bot = new BotInstanceData();
        bot.setFirst("Ada");
        bot.setLast("Actor");
        bot.setContainerIds(List.of("container-1", "container-2"));
        repo.save(bot);

        var dockerService = new RecordingDockerService();
        dockerService.setStatus(new ContainerStatus("container-1", "exited", false, "actor-1"));
        dockerService.setStatus(new ContainerStatus("container-2", "created", false, "actor-2"));

        new BotProvisioningService(
                repo,
                null,
                null,
                dockerService,
                null,
                null,
                props,
                new Appearances());

        assertEquals(List.of("container-1", "container-2"), dockerService.started);
        assertEquals(List.of(), dockerService.attachedLogs);
    }

    @Test
    void startupReconnectAttachesLogsForAlreadyRunningContainers() {
        var props = new SpawnerProperties();
        props.setDataDir(tempDir);
        props.setOpensimCreateBotUser(false);

        var repo = new BotStateRepository(new ObjectMapper(), props);
        var bot = new BotInstanceData();
        bot.setFirst("Ada");
        bot.setLast("Actor");
        bot.setContainerIds(List.of("container-1", "container-2"));
        repo.save(bot);

        var dockerService = new RecordingDockerService();
        dockerService.setStatus(new ContainerStatus("container-1", "running", true, "actor-1"));
        dockerService.setStatus(new ContainerStatus("container-2", "running", true, "actor-2"));

        new BotProvisioningService(
                repo,
                null,
                null,
                dockerService,
                null,
                null,
                props,
                new Appearances());

        assertEquals(List.of(), dockerService.started);
        assertEquals(List.of("container-1", "container-2"), dockerService.attachedLogs);
    }

    @Test
    void stopBotStopsAllKnownContainers() {
        var props = new SpawnerProperties();
        props.setDataDir(tempDir);
        props.setOpensimCreateBotUser(false);

        var repo = new BotStateRepository(new ObjectMapper(), props);
        var bot = new BotInstanceData();
        bot.setFirst("Ada");
        bot.setLast("Actor");
        bot.setContainerIds(List.of("container-1", "container-2"));
        repo.save(bot);

        var dockerService = new RecordingDockerService();
        var service = new BotProvisioningService(
                repo,
                null,
                null,
                dockerService,
                null,
                null,
                props,
                new Appearances());

        service.stopBot("Ada", "Actor");

        assertEquals(List.of("container-1", "container-2"), dockerService.stopped);
    }

    @Test
    void restartBotReturnsNotFoundForUnknownBot() {
        var props = new SpawnerProperties();
        props.setDataDir(tempDir);
        props.setOpensimCreateBotUser(false);

        var service = new BotProvisioningService(
                new BotStateRepository(new ObjectMapper(), props),
                null,
                null,
                new RecordingDockerService(),
                null,
                null,
                props,
                new Appearances());

        var ex = assertThrows(ResponseStatusException.class, () -> service.restartBot("No", "Bot"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void deleteBotRemovesContainersAndBotScopedVolumes() {
        var props = new SpawnerProperties();
        props.setDataDir(tempDir);
        props.setOpensimCreateBotUser(false);

        var repo = new BotStateRepository(new ObjectMapper(), props);
        var bot = new BotInstanceData();
        bot.setFirst("Ada");
        bot.setLast("Actor");
        bot.setContainerIds(List.of("container-1", "container-2"));
        repo.save(bot);

        var dockerService = new RecordingDockerService();
        var service = new BotProvisioningService(
                repo,
                null,
                null,
                dockerService,
                null,
                null,
                props,
                new Appearances());

        service.deleteBot("Ada", "Actor");

        assertEquals(List.of("container-1", "container-2"), dockerService.removed);
        assertEquals("-Ada-Actor", dockerService.removedVolumeSuffix);
    }
}
