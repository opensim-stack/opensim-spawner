package uk.co.bithatch.opensim.spawner.state;

import java.util.Collection;
import java.util.Optional;

import uk.co.bithatch.opensim.spawner.domain.DomainObject;

public interface StateRepository<T extends DomainObject> {

	default boolean exists(String name) {
		return list().stream().anyMatch(o -> o.getName().equals(name));
	}

	Optional<T> load(String name);

	void delete(String name);

	Collection<T> list();


}
