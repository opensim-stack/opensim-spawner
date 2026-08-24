package uk.co.bithatch.opensim.spawner.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.springframework.stereotype.Service;

@Service
public class OARs extends AbstractCfg {
	
	public record OAR(String name, String archivePath, String infoPath, int sx, int sy) { }
  public record OARDescriptor(String key, String name, int sx, int sy) { }

    private final List<String> oarNames;
    private final Map<String, OAR> oarsByKey;

    public OARs() {
    	var properties = loadProperties("oars.properties");
    	oarNames = List.copyOf(parseNames("oars", properties));
    	oarsByKey = Map.copyOf(parseOARMap(properties, oarNames));
    }

    public List<String> listNames() {
        return oarNames;
    }

    public List<OARDescriptor> listDescriptors() {
        return oarNames.stream()
                .map((key) -> {
                    var oar = oarsByKey.get(key);
                    return new OARDescriptor(key, oar.name(), oar.sx(), oar.sy());
                })
                .toList();
    }

    public OAR getOAR(String name) {
        var normalizedName = normalize(name);
        if (normalizedName.isEmpty()) {
            return null;
        }

        return oarsByKey.get(normalizedName);
    }

    private static Map<String, OAR> parseOARMap(Properties properties, List<String> oarNames) {
        var map = new LinkedHashMap<String, OAR>();
        for (var oarName : oarNames) {
        	var name = properties.getProperty(oarName + ".name", oarName);
        	var x = Integer.parseInt(properties.getProperty(oarName + ".x", "1"));
        	var y = Integer.parseInt(properties.getProperty(oarName + ".y", "1"));
        	var path = properties.getProperty(oarName, String.format("/oars/OAR-%s(%dx%d).tgz", name.replace(' ','-'), x, y)); 
                    var info = properties.getProperty(oarName + ".info", String.format("/oars/OAR-%s(%dx%d).txt", name.replace(' ','-'), x, y));
        	map.put(oarName, new OAR(
        			name,
        			path, info, x, y));
        }
        return map;
    }
}