package org.eclipse.microprofile.config;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A stand-in for the MicroProfile Config API (see {@link Config}): the values live in {@link #VALUES}. */
public final class ConfigProvider {

    public static final Map<String, String> VALUES = new ConcurrentHashMap<>();

    private ConfigProvider() {
    }

    public static Config getConfig() {
        return new Config() {
            @Override
            public <T> Optional<T> getOptionalValue(String name, Class<T> type) {
                return Optional.ofNullable(type.cast(VALUES.get(name)));
            }
        };
    }
}
