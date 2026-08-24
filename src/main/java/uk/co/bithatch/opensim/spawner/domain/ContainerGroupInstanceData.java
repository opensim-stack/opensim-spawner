package uk.co.bithatch.opensim.spawner.domain;

import java.util.List;

public abstract class ContainerGroupInstanceData<LVL extends Enum<LVL>> {
    private List<String> containerIds = List.of();
    private LVL level;

	public abstract String displayName();

    public final List<String> getContainerIds() {
        return containerIds;
    }

    public final void setContainerIds(List<String> containerIds) {
        this.containerIds = containerIds == null ? List.of() : List.copyOf(containerIds);
    }

	public LVL getLevel() {
		return level;
	}

	public void setLevel(LVL level) {
		this.level = level;
	}
}
