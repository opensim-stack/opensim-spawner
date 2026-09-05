package uk.co.bithatch.opensim.spawner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OpensimSpawnerApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpensimSpawnerApplication.class, args);
    }
}
