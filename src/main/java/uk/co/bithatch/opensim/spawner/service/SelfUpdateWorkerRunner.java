package uk.co.bithatch.opensim.spawner.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SelfUpdateWorkerRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SelfUpdateWorkerRunner.class);

    private final DockerService dockerService;

    public SelfUpdateWorkerRunner(DockerService dockerService) {
        this.dockerService = dockerService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!Boolean.parseBoolean(readEnv(DockerService.ENV_SELF_UPDATE_WORKER))) {
            return;
        }

        var targetContainer = readEnv(DockerService.ENV_SELF_UPDATE_TARGET_CONTAINER);
        var targetImage = readEnv(DockerService.ENV_SELF_UPDATE_TARGET_IMAGE);
        var pullBeforeRecreate = Boolean.parseBoolean(readEnv(DockerService.ENV_SELF_UPDATE_PULL));

        if (targetContainer.isBlank() || targetImage.isBlank()) {
            LOG.error("Self-update worker requested but target container/image is missing.");
            System.exit(1);
            return;
        }

        var exitCode = 0;
        try {
            LOG.info("Starting self-update worker for container {} using image {}.", targetContainer, targetImage);
            if (pullBeforeRecreate && !isLocalImage(targetImage)) {
                dockerService.pullImage(targetImage);
            }
            dockerService.recreateContainer(targetContainer, targetImage);
            LOG.info("Self-update worker completed update for container {}.", targetContainer);
        } catch (RuntimeException e) {
            exitCode = 1;
            LOG.error("Self-update worker failed for container {}.", targetContainer, e);
        }

        System.exit(exitCode);
    }

    private static boolean isLocalImage(String imageRef) {
        return "local".equalsIgnoreCase(DockerService.imageTag(imageRef));
    }

    private static String readEnv(String key) {
        var raw = System.getenv(key);
        return raw == null ? "" : raw.trim();
    }
}
