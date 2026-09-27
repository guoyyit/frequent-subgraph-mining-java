package com.github.guoyyit.frequentsubgraphminingjava.mining;

import java.util.Set;

/**
 * Label filtering directly on a canonical key.
 *
 * <p>A canonical key has the form
 * {@code n:<label0>,<label1>,...|e:<u>-><v>:<label>,...}. Every node and edge label
 * appearing in the pattern must be contained in the respective allowed set
 * (empty/null set = unrestricted).
 */
public final class PatternKeyFilters {

    private PatternKeyFilters() {
    }

    public static boolean matches(String canonicalKey,
                                  Set<String> allowedNodeLabels,
                                  Set<String> allowedEdgeLabels) {
        boolean nodesRestricted = allowedNodeLabels != null && !allowedNodeLabels.isEmpty();
        boolean edgesRestricted = allowedEdgeLabels != null && !allowedEdgeLabels.isEmpty();
        if (!nodesRestricted && !edgesRestricted) {
            return true;
        }
        int bar = canonicalKey.indexOf('|');
        String nodePart = canonicalKey.substring(2, bar);
        if (nodesRestricted && !allLabelsIn(nodePart, allowedNodeLabels)) {
            return false;
        }
        if (edgesRestricted) {
            String edgePart = canonicalKey.substring(bar + 3);
            if (!edgePart.isEmpty()) {
                for (String edge : edgePart.split(",", -1)) {
                    int colon = edge.lastIndexOf(':');
                    String label = edge.substring(colon + 1);
                    if (!allowedEdgeLabels.contains(label)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean allLabelsIn(String commaLabels, Set<String> allowed) {
        if (commaLabels.isEmpty()) {
            return true;
        }
        for (String label : commaLabels.split(",", -1)) {
            if (!allowed.contains(label)) {
                return false;
            }
        }
        return true;
    }
}
