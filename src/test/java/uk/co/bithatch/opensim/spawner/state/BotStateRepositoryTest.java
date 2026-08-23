package uk.co.bithatch.opensim.spawner.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.domain.BotInstanceData;
import uk.co.bithatch.opensim.spawner.domain.BotLevel;

class BotStateRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void savesLoadsAndListsStateFiles() {
        var repo = new BotStateRepository(new ObjectMapper(), tempDir);

        var bot = new BotInstanceData();
        bot.setFirst("Ada");
        bot.setLast("Builder");
        bot.setLevel(BotLevel.BUILDER);
        bot.setPassword("secret");

        repo.save(bot);

        assertTrue(repo.exists("Ada", "Builder"));
        var loaded = repo.load("Ada", "Builder").orElseThrow();
        assertEquals(BotLevel.BUILDER, loaded.getLevel());
        assertEquals(1, repo.list().size());
    }
}
