package uk.co.bithatch.opensim.spawner.service;

import java.util.Map;

public interface OpenSimService {

    void createUser(String first, String last, String password, String email, String uuid, String model);

    void loadInventoryArchive(String first, String last, String inventoryPath, String password, String archivePath);

    void loadRegionArchive(String archivePath);

    void deleteUser(String first, String last);

    Map<String, String> showAccount(String first, String last);

    void resetUserPassword(String first, String last, String password);
}
