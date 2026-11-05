package eu.hansolo.mcp.tools;

/** Gives a tool method the object to run on, for exactly one call. CDI supplies contextual references; tests supply a plain object. */
@FunctionalInterface
public interface BeanSource {

    Lease acquire();

    static BeanSource of(Object instance) {
        return () -> new Lease() {
            @Override
            public Object instance() {
                return instance;
            }

            @Override
            public void close() {
            }
        };
    }


    /** An acquired instance; closing it releases whatever the container created for the call. */
    interface Lease extends AutoCloseable {

        Object instance();

        @Override
        void close();
    }
}
