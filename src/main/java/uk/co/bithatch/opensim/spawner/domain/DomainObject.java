package uk.co.bithatch.opensim.spawner.domain;

public interface DomainObject {

	String getName();
	
	default String displayName() {
		return getName();
	}

}