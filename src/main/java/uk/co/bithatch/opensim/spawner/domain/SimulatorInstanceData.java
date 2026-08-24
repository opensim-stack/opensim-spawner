package uk.co.bithatch.opensim.spawner.domain;

public class SimulatorInstanceData extends ContainerGroupInstanceData<SimulatorLevel> {
	private int port;
	private String name;
	private String ownerUuid;
	private String ownerFirst;
	private String ownerLast;
	private String ownerEmail;
	private String ownerPassword;
	private RegionInstanceData[] regions;
	
	public RegionInstanceData[] getRegions() {
		return regions;
	}

	public void setRegions(RegionInstanceData[] regions) {
		this.regions = regions;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getOwnerUuid() {
		return ownerUuid;
	}

	public void setOwnerUuid(String ownerUuid) {
		this.ownerUuid = ownerUuid;
	}

	public String getOwnerFirst() {
		return ownerFirst;
	}

	public void setOwnerFirst(String ownerFirst) {
		this.ownerFirst = ownerFirst;
	}

	public String getOwnerLast() {
		return ownerLast;
	}

	public void setOwnerLast(String ownerLast) {
		this.ownerLast = ownerLast;
	}

	public String getOwnerEmail() {
		return ownerEmail;
	}

	public void setOwnerEmail(String ownerEmail) {
		this.ownerEmail = ownerEmail;
	}

	public String getOwnerPassword() {
		return ownerPassword;
	}

	public void setOwnerPassword(String ownerPassword) {
		this.ownerPassword = ownerPassword;
	}

	@Override
	public String displayName() {
		return name;
	}

}
