package uk.co.bithatch.opensim.spawner.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.BotInstanceData;
import uk.co.bithatch.opensim.spawner.domain.BotLevel;

class BotLevelProfileServiceTest {

    @TempDir
    java.nio.file.Path tempDir;

    @Test
    void resolvesDefaultProfileWithDynamicPortsAndWorkspaceBotsPath() throws Exception {
        var props = new SpawnerProperties();
        props.setConfigDir(tempDir.resolve("config"));
        Files.createDirectories(props.getConfigDir());

        var service = new BotLevelProfileService(new ObjectMapper(), props, new TemplateResolver());

        var bot = new BotInstanceData();
        bot.setFirst("Ada");
        bot.setLast("Actor");
        bot.setLevel(BotLevel.BUILDER);
        bot.setPassword("pw");

        var plan = service.resolvePlan(bot, Map.of());
        assertEquals(2, plan.containers().size());
        var hasBotsPath = plan.containers().stream()
                .flatMap(container -> container.getVolumes().entrySet().stream())
                .anyMatch(entry -> entry.getValue().contains("/workspace"));
        assertTrue(hasBotsPath);
    }
}
