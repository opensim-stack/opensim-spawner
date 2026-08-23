package uk.co.bithatch.opensim.spawner.domain;

import java.util.List;

public record ResolvedBotPlan(
        BotLevel level,
        List<ContainerSpec> containers) {
}
