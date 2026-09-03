package uk.co.bithatch.opensim.spawner.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.RegionInstanceData;
import uk.co.bithatch.opensim.spawner.domain.SimulatorInstanceData;
import uk.co.bithatch.opensim.spawner.domain.SimulatorLevel;
import uk.co.bithatch.opensim.spawner.state.SimulatorStateRepository;

class SimulatorRegionSyncServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void synchronizeSimulatorReplacesRegionsFromIniAndPreservesKnownMetadata() throws Exception {
        var props = new SpawnerProperties();
        props.setConfigDir(tempDir.resolve("config"));
        props.setDataDir(tempDir.resolve("data"));

        var repository = new SimulatorStateRepository(new ObjectMapper(), props);

        var simulator = new SimulatorInstanceData();
        simulator.setName("TerraSim");
        simulator.setLevel(SimulatorLevel.STANDALONE);

        var staleRegion = new RegionInstanceData();
        staleRegion.setName("TerraSim");
        staleRegion.setUuid("09fc977f-41f2-46f9-96f7-453afea15b33");
        staleRegion.setX(1);
        staleRegion.setY(2);
        staleRegion.setPort(42);
        staleRegion.setWidth(2);
        staleRegion.setHeight(3);
        staleRegion.setOar("blank");
        simulator.setRegions(new RegionInstanceData[] { staleRegion });
        repository.save(simulator);

        var regionsDir = props.getConfigDir().resolve("sims").resolve("TerraSim").resolve("Regions");
        Files.createDirectories(regionsDir);
        Files.writeString(regionsDir.resolve("Region.ini"), """
                [Dragon Fyre]
                RegionUUID = 4cfec434-acb0-40d5-a539-c94b65779741
                Location = 1002,1000
                InternalAddress = 0.0.0.0
                InternalPort = 9002
                ExternalHostName = terra

                [TerraSim]
                RegionUUID = 09fc977f-41f2-46f9-96f7-453afea15b33
                Location = 1000,1000
                InternalAddress = 0.0.0.0
                InternalPort = 9000
                ExternalHostName = terra
                """);

        var service = new SimulatorRegionSyncService(props, repository);
        service.synchronizeSimulator("TerraSim");

        var synchronizedSim = repository.load("TerraSim").orElseThrow();
        assertNotNull(synchronizedSim.getRegions());
        assertEquals(2, synchronizedSim.getRegions().length);

        var first = synchronizedSim.getRegions()[0];
        var second = synchronizedSim.getRegions()[1];

        assertEquals("TerraSim", first.getName());
        assertEquals("09fc977f-41f2-46f9-96f7-453afea15b33", first.getUuid());
        assertEquals(1000, first.getX());
        assertEquals(1000, first.getY());
        assertEquals(9000, first.getPort());
        assertEquals(2, first.getWidth());
        assertEquals(3, first.getHeight());
        assertEquals("blank", first.getOar());

        assertEquals("Dragon Fyre", second.getName());
        assertEquals("4cfec434-acb0-40d5-a539-c94b65779741", second.getUuid());
        assertEquals(1002, second.getX());
        assertEquals(1000, second.getY());
        assertEquals(9002, second.getPort());
    }
}
