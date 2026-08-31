package uk.co.bithatch.opensim.spawner.domain;

public class AddOnInstanceData extends ContainerGroupInstanceData<AddOnLevel> {
	
	private String name;
	private String gridServiceSimulatorName;
	

	public String getName() {
		return name;
	}


	public void setName(String name) {
		this.name = name;
	}

	public String getGridServiceSimulatorName() {
		return gridServiceSimulatorName;
	}

	public void setGridServiceSimulatorName(String gridServiceSimulatorName) {
		this.gridServiceSimulatorName = gridServiceSimulatorName;
	}


	@Override
	public String displayName() {
		return name;
	}
}
