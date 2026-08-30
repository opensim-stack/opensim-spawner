package uk.co.bithatch.opensim.spawner.state;

import java.util.Collection;
import java.util.Optional;

import uk.co.bithatch.opensim.spawner.domain.DomainObject;

public interface StateRepository<T extends DomainObject> {

	boolean exists(String name);

	Optional<T> load(String name);

	void delete(String name);

	Collection<T> list();


}
