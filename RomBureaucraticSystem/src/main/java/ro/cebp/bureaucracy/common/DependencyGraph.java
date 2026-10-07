package ro.cebp.bureaucracy.common;

import ro.cebp.bureaucracy.common.model.DocumentType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency graph between document types ("to get X you first need Y and Z").
 *
 * <p>All plans are computed once, in the constructor. After construction the object
 * is <b>immutable</b>: every field is {@code final} and holds an unmodifiable collection.
 * Because of the final-field semantics of the Java Memory Model, any thread that obtains
 * a reference to a constructed graph sees it fully initialized, so client threads can
 * share it with no locks.
 */
public final class DependencyGraph {

    private enum Color { WHITE, GRAY, BLACK }

    private final Map<String, DocumentType> documents;
    private final Map<String, List<DocumentType>> plans;

    /**
     * @throws ConfigException if a document requires an unknown document or the
     *                         dependencies contain a cycle
     */
    public DependencyGraph(Map<String, DocumentType> documents) {
        this.documents = Map.copyOf(documents);

        Map<String, List<DocumentType>> computed = new HashMap<>();
        for (String name : this.documents.keySet()) {
            computed.put(name, computePlan(name));
        }
        this.plans = Map.copyOf(computed);
    }

    /**
     * Every document a client must obtain, in an order in which each one can be
     * obtained: all dependencies come before the documents that need them, the
     * target is last, and a document needed by several others appears only once.
     */
    public List<DocumentType> planFor(String target) {
        List<DocumentType> plan = plans.get(target);
        if (plan == null) {
            throw new IllegalArgumentException("Unknown document: " + target);
        }
        return plan;
    }

    public Map<String, DocumentType> documents() {
        return documents;
    }

    /** Topological sort restricted to the documents reachable from {@code target} (DFS post-order). */
    private List<DocumentType> computePlan(String target) {
        Map<String, Color> colors = new HashMap<>();
        Deque<String> path = new ArrayDeque<>();
        // LinkedHashMap keeps the post-order and ignores documents already added.
        Map<String, DocumentType> ordered = new LinkedHashMap<>();
        visit(target, colors, path, ordered);
        return List.copyOf(ordered.values());
    }

    private void visit(String name, Map<String, Color> colors, Deque<String> path,
                       Map<String, DocumentType> ordered) {
        DocumentType doc = documents.get(name);
        if (doc == null) {
            throw new ConfigException("Document '" + path.peekLast() + "' requires unknown document '" + name + "'");
        }

        Color color = colors.getOrDefault(name, Color.WHITE);
        if (color == Color.BLACK) {
            return;
        }
        if (color == Color.GRAY) {
            // We came back to a document that is still on the DFS stack: that is a cycle.
            List<String> cycle = new ArrayList<>(path);
            cycle = cycle.subList(cycle.indexOf(name), cycle.size());
            cycle.add(name);
            throw new ConfigException("Dependency cycle: " + String.join(" -> ", cycle));
        }

        colors.put(name, Color.GRAY);
        path.addLast(name);
        for (String required : doc.requires()) {
            visit(required, colors, path, ordered);
        }
        path.removeLast();
        colors.put(name, Color.BLACK);
        ordered.put(name, doc);
    }
}
