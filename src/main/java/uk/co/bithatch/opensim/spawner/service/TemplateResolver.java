package uk.co.bithatch.opensim.spawner.service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class TemplateResolver {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("%([^%]+)%");

    public String resolve(String template, Map<String, String> values) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        var matcher = TOKEN_PATTERN.matcher(template);
        var buffer = new StringBuffer();
        while (matcher.find()) {
            var key = matcher.group(1);
            var replacement = values == null ? "" : values.get(key);
            if (replacement == null) {
                replacement = "";
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
