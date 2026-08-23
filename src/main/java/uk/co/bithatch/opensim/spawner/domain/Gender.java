package uk.co.bithatch.opensim.spawner.domain;

public enum Gender {
    MALE,
    FEMALE,
    NEUTRAL;

    public static Gender fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Gender.valueOf(value.trim().toUpperCase());
    }
}