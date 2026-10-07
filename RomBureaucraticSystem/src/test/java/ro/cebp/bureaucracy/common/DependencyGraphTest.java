package ro.cebp.bureaucracy.common;

import org.junit.jupiter.api.Test;
import ro.cebp.bureaucracy.common.model.DocumentType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyGraphTest {

    private static Map<String, DocumentType> docs(String... specs) {
        // spec format: "Name:Req1,Req2"
        Map<String, DocumentType> map = new HashMap<>();
        for (String spec : specs) {
            String[] parts = spec.split(":", -1);
            List<String> requires = parts[1].isEmpty() ? List.of() : List.of(parts[1].split(","));
            map.put(parts[0], new DocumentType(parts[0], "Office", requires));
        }
        return map;
    }

    private static List<String> names(List<DocumentType> plan) {
        return plan.stream().map(DocumentType::name).toList();
    }

    @Test
    void documentWithoutRequirementsHasSingleStepPlan() {
        DependencyGraph graph = new DependencyGraph(docs("A:"));
        assertEquals(List.of("A"), names(graph.planFor("A")));
    }

    @Test
    void dependenciesComeBeforeTheDocumentsThatNeedThem() {
        DependencyGraph graph = new DependencyGraph(docs("Birth:", "Address:Birth", "Id:Birth,Address", "Tax:Id"));
        List<String> plan = names(graph.planFor("Tax"));

        assertEquals("Tax", plan.getLast());
        assertTrue(plan.indexOf("Birth") < plan.indexOf("Address"));
        assertTrue(plan.indexOf("Address") < plan.indexOf("Id"));
        assertTrue(plan.indexOf("Id") < plan.indexOf("Tax"));
    }

    @Test
    void sharedDependencyAppearsOnlyOnce() {
        // Diamond: D needs B and C, both need A.
        DependencyGraph graph = new DependencyGraph(docs("A:", "B:A", "C:A", "D:B,C"));
        List<String> plan = names(graph.planFor("D"));

        assertEquals(4, plan.size());
        assertEquals(1, plan.stream().filter("A"::equals).count());
    }

    @Test
    void cycleIsRejectedWithItsPath() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> new DependencyGraph(docs("A:B", "B:C", "C:A")));
        assertTrue(e.getMessage().contains("cycle"), e.getMessage());
    }

    @Test
    void selfDependencyIsACycle() {
        assertThrows(ConfigException.class, () -> new DependencyGraph(docs("A:A")));
    }

    @Test
    void unknownRequirementIsRejected() {
        ConfigException e = assertThrows(ConfigException.class, () -> new DependencyGraph(docs("A:Ghost")));
        assertTrue(e.getMessage().contains("Ghost"), e.getMessage());
    }

    @Test
    void unknownTargetIsRejected() {
        DependencyGraph graph = new DependencyGraph(docs("A:"));
        assertThrows(IllegalArgumentException.class, () -> graph.planFor("Nope"));
    }
}
