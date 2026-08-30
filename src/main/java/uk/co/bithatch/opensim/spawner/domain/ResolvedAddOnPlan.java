package uk.co.bithatch.opensim.spawner.domain;

import java.util.List;

public record ResolvedAddOnPlan(
        AddOnLevel level,
        List<ContainerSpec> containers) implements Plan {
}
