package uk.co.bithatch.opensim.spawner.domain;

import java.util.List;

public record ResolvedSimulatorPlan(
        SimulatorLevel level,
        List<ContainerSpec> containers) implements Plan {
}
