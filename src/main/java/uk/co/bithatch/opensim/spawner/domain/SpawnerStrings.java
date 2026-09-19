package uk.co.bithatch.opensim.spawner.domain;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class SpawnerStrings {

	public static String normalizeBotName(String value) {
		var normalized = normalize(value);
		return normalized.isBlank() ? "*" : normalized;
	}

	public static String normalize(String value) {
		return value == null ? "" : value.trim();
	}

	public static String normalizeRequiredName(String field, String value) {
		var normalized = normalize(value);
		if (normalized.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: " + field + ".");
		}
		return normalized;
	}
}
