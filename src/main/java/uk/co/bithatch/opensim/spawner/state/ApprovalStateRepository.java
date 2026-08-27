package uk.co.bithatch.opensim.spawner.state;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.ApprovalInstanceData;

@Component
public class ApprovalStateRepository extends AbstractStateRepository<ApprovalInstanceData> {

    public static String key(String first, String last) {
        return first + "-" + last;
    }

    @Autowired
    public ApprovalStateRepository(ObjectMapper objectMapper, SpawnerProperties properties) {
        super(objectMapper, properties.getDataDir().resolve("approvals"), ApprovalInstanceData.class);
    }

    ApprovalStateRepository(ObjectMapper objectMapper, Path dataDir) {
        super(objectMapper, dataDir, ApprovalInstanceData.class);
    }
}
