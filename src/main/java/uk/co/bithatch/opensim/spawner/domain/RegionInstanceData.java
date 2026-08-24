package uk.co.bithatch.opensim.spawner.domain;

public class RegionInstanceData {

	private int x = 1000;
	private int y = 1000;
	private int width = 1;
	private int height = 1;
	private String uuid;
	private String oar;

	public int getWidth() {
		return width;
	}

	public void setWidth(int width) {
		this.width = width;
	}

	public int getHeight() {
		return height;
	}

	public void setHeight(int height) {
		this.height = height;
	}

	public int getX() {
		return x;
	}

	public void setX(int x) {
		this.x = x;
	}

	public int getY() {
		return y;
	}

	public void setY(int y) {
		this.y = y;
	}

	public String getUuid() {
		return uuid;
	}

	public void setUuid(String uuid) {
		this.uuid = uuid;
	}

	public String getOar() {
		return oar;
	}

	public void setOar(String oar) {
		this.oar = oar;
	}

}
