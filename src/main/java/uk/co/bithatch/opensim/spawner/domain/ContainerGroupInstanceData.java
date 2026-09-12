package uk.co.bithatch.opensim.spawner.domain;

import java.util.List;
import java.util.Map;

public abstract class ContainerGroupInstanceData<LVL extends Enum<LVL>> implements DomainObject {
    private List<String> containerIds = List.of();
    private LVL level;
    private Map<String, String> requestFields;

    public final List<String> getContainerIds() {
        return containerIds;
    }

    public final void setContainerIds(List<String> containerIds) {
        this.containerIds = containerIds == null ? List.of() : List.copyOf(containerIds);
    }

	public final LVL getLevel() {
		return level;
	}

	public final void setLevel(LVL level) {
		this.level = level;
	}

    public final Map<String, String> getRequestFields() {
		return requestFields;
	}

	public final void setRequestFields(Map<String, String> requestFields) {
		this.requestFields = requestFields;
	}
}
