package uk.co.bithatch.opensim.spawner.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.co.bithatch.opensim.spawner.state.BotStateRepository.key;

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

        String key = key("Ada", "Builder");
		assertTrue(repo.exists(key));
        var loaded = repo.load(key).orElseThrow();
        assertEquals(BotLevel.BUILDER, loaded.getLevel());
        assertEquals(1, repo.list().size());
    }
}
