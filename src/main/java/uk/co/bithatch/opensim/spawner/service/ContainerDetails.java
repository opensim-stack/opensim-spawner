package uk.co.bithatch.opensim.spawner.service;

public record ContainerDetails(String id, String name, String displayName, String status, boolean running, String[] env, String image) {
	public ContainerDetails(String id, String name, String status, boolean running, String[] env, String image) {
		this(id, name, name.replaceFirst("/^", ""), status, running, env, image);
	}
}
