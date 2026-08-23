package uk.co.bithatch.opensim.spawner.service;

import java.security.SecureRandom;

import org.springframework.stereotype.Service;

@Service
public class RandomPasswordService {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int DEFAULT_LENGTH = 20;

    private final SecureRandom random = new SecureRandom();

    public String nextPassword() {
        var out = new StringBuilder(DEFAULT_LENGTH);
        for (int i = 0; i < DEFAULT_LENGTH; i++) {
            out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }
}
