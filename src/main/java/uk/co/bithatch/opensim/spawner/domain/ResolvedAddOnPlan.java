package uk.co.bithatch.opensim.spawner.domain;

import java.util.List;

public record ResolvedAddOnPlan(
        ContainerLevel level,
        List<ContainerSpec> containers) implements Plan {
}
