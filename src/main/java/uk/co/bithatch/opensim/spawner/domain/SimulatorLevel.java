package uk.co.bithatch.opensim.spawner.domain;

public enum SimulatorLevel {
    ROBUST,
    GRID,
    STANDALONE;
	
	public boolean providesGridService() {
		switch (this) {
			case STANDALONE, ROBUST -> {
				return true;
			}
			case GRID  -> {
				return false;
			}
		}
		return false; // default case, should not be reached
	}

	public boolean requiresRegion() {
		switch (this) {
			case STANDALONE, GRID -> {
				return true;
			}
			case ROBUST -> {
				return false;
			}
		}
		return false; // default case, should not be reached
	}

    public static SimulatorLevel fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return STANDALONE;
        }
        return SimulatorLevel.valueOf(value.trim().toUpperCase());
    }
}
