package uk.co.bithatch.opensim.spawner.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import uk.co.bithatch.opensim.spawner.domain.AddOnLevel;
import uk.co.bithatch.opensim.spawner.state.AddOnInstanceStateRepository;
import uk.co.bithatch.opensim.spawner.state.BotStateRepository;
import uk.co.bithatch.opensim.spawner.state.SimulatorStateRepository;

@Service
public class ContainerReferenceMigrationService {

    private static final Logger LOG = LoggerFactory.getLogger(ContainerReferenceMigrationService.class);

    private final BotStateRepository botStateRepository;
    private final SimulatorStateRepository simulatorStateRepository;
    private final AddOnInstanceStateRepository addOnInstanceStateRepository;
    private final BotLevelProfileService botLevelProfileService;
    private final SimulatorLevelProfileService simulatorLevelProfileService;
    private final AddOnProfileService addOnProfileService;

    public ContainerReferenceMigrationService(BotStateRepository botStateRepository,
            SimulatorStateRepository simulatorStateRepository,
            AddOnInstanceStateRepository addOnInstanceStateRepository,
            BotLevelProfileService botLevelProfileService,
            SimulatorLevelProfileService simulatorLevelProfileService,
            AddOnProfileService addOnProfileService) {
        this.botStateRepository = botStateRepository;
        this.simulatorStateRepository = simulatorStateRepository;
        this.addOnInstanceStateRepository = addOnInstanceStateRepository;
        this.botLevelProfileService = botLevelProfileService;
        this.simulatorLevelProfileService = simulatorLevelProfileService;
        this.addOnProfileService = addOnProfileService;
    }

    @PostConstruct
    public void migrateTrackedContainerReferencesOnStartup() {
        migrateBots();
        migrateAddOnsAndSimulators();
    }

    private void migrateBots() {
        for (var bot : botStateRepository.list()) {
            try {
                var expectedRefs = botLevelProfileService.resolvePlan(bot, Map.of()).containers().stream()
                        .map(spec -> spec.getName() == null ? "" : spec.getName().trim())
                        .filter(name -> !name.isEmpty())
                        .toList();
                saveIfChanged("bot " + bot.displayName(), bot.getContainerIds(), expectedRefs, () -> botStateRepository.save(bot),
                        bot::setContainerIds);
            } catch (RuntimeException e) {
                LOG.warn("Skipping container-reference migration for bot {} because plan resolution failed.",
                        bot.displayName(),
                        e);
            }
        }
    }

    private void migrateAddOnsAndSimulators() {
        var addOnNamesBySimulator = new LinkedHashMap<String, List<String>>();

        for (var addOn : addOnInstanceStateRepository.list()) {
            try {
                var expectedRefs = addOnProfileService.resolvePlan(addOn, Map.of()).containers().stream()
                        .map(spec -> spec.getName() == null ? "" : spec.getName().trim())
                        .filter(name -> !name.isEmpty())
                        .toList();

                saveIfChanged("add-on " + addOn.displayName(),
                        addOn.getContainerIds(),
                        expectedRefs,
                        () -> addOnInstanceStateRepository.save(addOn),
                        addOn::setContainerIds);

                if (addOn.getLevel() == AddOnLevel.SIMULATOR
                        && addOn.getGridServiceSimulatorName() != null
                        && !addOn.getGridServiceSimulatorName().isBlank()) {
                    addOnNamesBySimulator.computeIfAbsent(addOn.getGridServiceSimulatorName(), _ignored -> new ArrayList<>())
                            .addAll(expectedRefs);
                }
            } catch (RuntimeException e) {
                LOG.warn("Skipping container-reference migration for add-on {} because plan resolution failed.",
                        addOn.displayName(),
                        e);
            }
        }

        for (var sim : simulatorStateRepository.list()) {
            try {
                var mergedRefs = new LinkedHashSet<String>();
                mergedRefs.addAll(simulatorLevelProfileService.resolvePlan(sim, Map.of()).containers().stream()
                        .map(spec -> spec.getName() == null ? "" : spec.getName().trim())
                        .filter(name -> !name.isEmpty())
                        .toList());
                mergedRefs.addAll(addOnNamesBySimulator.getOrDefault(sim.getName(), List.of()));

                saveIfChanged("simulator " + sim.displayName(),
                        sim.getContainerIds(),
                        new ArrayList<>(mergedRefs),
                        () -> simulatorStateRepository.save(sim),
                        sim::setContainerIds);
            } catch (RuntimeException e) {
                LOG.warn("Skipping container-reference migration for simulator {} because plan resolution failed.",
                        sim.displayName(),
                        e);
            }
        }
    }

    private static void saveIfChanged(String owner,
            List<String> currentRefs,
            List<String> expectedRefs,
            Runnable saver,
            java.util.function.Consumer<List<String>> setter) {
        var normalizedCurrent = currentRefs == null ? List.<String>of() : List.copyOf(currentRefs);
        var normalizedExpected = expectedRefs == null ? List.<String>of() : List.copyOf(expectedRefs);
        if (normalizedExpected.isEmpty() || normalizedExpected.equals(normalizedCurrent)) {
            return;
        }

        setter.accept(normalizedExpected);
        saver.run();
        LOG.info("Migrated tracked containers for {} from {} to {} stable name reference(s).",
                owner,
                normalizedCurrent.size(),
                normalizedExpected.size());
    }
}
