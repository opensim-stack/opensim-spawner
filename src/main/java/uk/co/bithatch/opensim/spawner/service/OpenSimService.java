package uk.co.bithatch.opensim.spawner.service;

public interface OpenSimService {

    void createUser(String first, String last, String password, String email, String uuid, String model);

    void loadInventoryArchive(String first, String last, String inventoryPath, String password, String archivePath);

    void deleteUser(String first, String last);
}
