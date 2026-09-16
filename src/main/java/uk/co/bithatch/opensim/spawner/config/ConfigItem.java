package uk.co.bithatch.opensim.spawner.config;

public record ConfigItem(VariableType type, String name, String description, String... choices) {
}
