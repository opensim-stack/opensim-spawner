package uk.co.bithatch.opensim.spawner.domain;

import java.util.List;
import java.util.Map;

public record ResolvedAddOnPlan(
        ContainerLevel level,
        List<ContainerSpec> containers,
        Map<String, String> variables) implements Plan {
}
