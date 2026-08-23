package uk.co.bithatch.opensim.spawner.domain;

public enum BotLevel {
    GOVERNOR,
    BUILDER,
    ACTOR;

    public static BotLevel fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return ACTOR;
        }
        return BotLevel.valueOf(value.trim().toUpperCase());
    }
}
