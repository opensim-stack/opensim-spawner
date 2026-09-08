package uk.co.bithatch.opensim.spawner.domain;

public class Component implements DomainObject {
	private String name;

	public Component() { }

	public final String getName() {
		return name;
	}

	public final void setName(String name) {
		this.name = name;
	}


}
