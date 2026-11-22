package org.eclipse.microprofile.config;

import java.util.Optional;

/** A stand-in for the MicroProfile Config API, so that the unit tests can show how {@code Settings} finds it by name. Test class path only. */
public interface Config {

    <T> Optional<T> getOptionalValue(String name, Class<T> type);
}
