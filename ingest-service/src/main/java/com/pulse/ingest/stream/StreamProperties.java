package com.pulse.ingest.stream;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pulse.stream")
public record StreamProperties(String key, String group, String consumer) {
}
