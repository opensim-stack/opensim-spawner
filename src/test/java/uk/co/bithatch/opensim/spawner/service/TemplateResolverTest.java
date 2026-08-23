package uk.co.bithatch.opensim.spawner.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

class TemplateResolverTest {

    @Test
    void resolvesKnownTokensAndClearsUnknown() {
        var resolver = new TemplateResolver();
        var resolved = resolver.resolve(
                "a=%bot.first%, b=%ports.metaverse2mcp%, c=%env.OPENSIM_LOGIN_URI%, d=%missing%",
                Map.of(
                        "bot.first", "Ada",
                        "ports.metaverse2mcp", "12347",
                        "env.OPENSIM_LOGIN_URI", "http://example/login"));

        assertEquals("a=Ada, b=12347, c=http://example/login, d=", resolved);
    }
}
