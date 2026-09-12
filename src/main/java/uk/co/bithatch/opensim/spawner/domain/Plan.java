package uk.co.bithatch.opensim.spawner.domain;

import java.util.List;
import java.util.Map;

public interface Plan {
	List<ContainerSpec> containers();
	Map<String, String> variables();
}
