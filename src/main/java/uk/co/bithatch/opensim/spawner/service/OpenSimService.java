package uk.co.bithatch.opensim.spawner.service;

import java.util.Map;
import java.util.Optional;

import uk.co.bithatch.opensim.jlib.OpensimRemoteAdminClient.AgentLocation;

import java.util.List;

public interface OpenSimService {

    record RegionData(String name, String id, int x, int y, String position, String size, int port, boolean ready, String estate, List<String> flags) {

		public RegionData withFlags(List<String> flags) {
			return new RegionData(name, id, x, y, position, size, port, ready, estate, flags);
		}
    }

    record EstateData(String name, String id, String ownerFirst, String ownerLast) {
    }

    record CreateRegionData(String name,
            int x,
            int y,
            boolean isPublic,
            boolean enableVoice,
            String estateName,
            Integer port,
            String oar,
            String estateOwnerFirst,
            String estateOwnerLast) {
    }

    record RegionOptionsData(boolean isPublic, boolean enableVoice) {
    }

    void createUser(String first, String last, String password, String email, String uuid, String model);

    void loadInventoryArchive(String first, String last, String inventoryPath, String password, String archivePath);

    void loadRegionArchive(String archivePath);

    void deleteUser(String first, String last);

    Map<String, String> showAccount(String first, String last);

    List<Map<String, String>> showActiveUsers();

    void resetUserPassword(String first, String last, String password);

	boolean authenticate(String first, String last, char[] password);

    List<RegionData> showRegions(String simulatorName);

    List<EstateData> showEstates(String simulatorName);

    RegionData createRegion(String simulatorName, CreateRegionData request);

    RegionData modifyRegion(String simulatorName, String regionId, RegionOptionsData request);

    void restartRegion(String simulatorName, String regionId);

    void closeRegion(String simulatorName, String regionId);

    void deleteRegion(String simulatorName, String regionId);

	Optional<AgentLocation> findAgentByName(String first, String last);

	Optional<AgentLocation> findAgentByUuid(String uuid);
}
