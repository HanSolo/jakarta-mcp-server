package eu.hansolo.mcp.itfixture;

import jakarta.enterprise.context.ApplicationScoped;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.api.McpTool;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** A tool that returns a plain bean with getters, as legacy services and JAX-RS resources do. */
@ApplicationScoped
public class BeanTools {

    public static class Pond {
        private final String name;

        public Pond(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    public static class Duck {
        public String getName() {
            return "Donald";
        }

        @McpParam(description = "Day of the first swim")
        public LocalDate getFirstSwim() {
            return LocalDate.of(2026, 11, 24);
        }

        public boolean isHungry() {
            return true;
        }

        public int getAge() {
            return 7;
        }

        public String getNickname() {
            return null;
        }

        public Optional<String> getOwner() {
            return Optional.empty();
        }

        public Pond getPond() {
            return new Pond("Duckburg Pond");
        }

        public List<Pond> getVisited() {
            return List.of(new Pond("A"), new Pond("B"));
        }
    }


    @McpTool(description = "Returns a duck as a plain bean")
    public Duck bean_duck() {
        return new Duck();
    }
}
