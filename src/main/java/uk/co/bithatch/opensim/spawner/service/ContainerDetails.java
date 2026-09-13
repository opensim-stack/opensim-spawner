package uk.co.bithatch.opensim.spawner.service;

import java.util.Map;

public record ContainerDetails(
		String id,
		String name,
		String displayName,
		String status,
		boolean running,
		String[] env,
		String image,
		String imageId,
		Map<String, String> labels) {
	public ContainerDetails(String id, String name, String status, boolean running, String[] env, String image) {
	this(id, name, name.replaceFirst("/^", ""), status, running, env, image, null, Map.of());
  }

  public ContainerDetails(String id, String name, String displayName, String status, boolean running, String[] env, String image) {
	this(id, name, displayName, status, running, env, image, null, Map.of());
	}
}
