package uk.co.bithatch.opensim.spawner.domain;

public class AddOnInstanceData extends ContainerGroupInstanceData<AddOnLevel> {
	
	private String name;
	

	public String getName() {
		return name;
	}


	public void setName(String name) {
		this.name = name;
	}


	@Override
	public String displayName() {
		return name;
	}
}
