package com.github.guoyyit.frequentsubgraphminingjava.mining;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical labeling of small connected labeled graphs.
 *
 * <p>The canonical key is the lexicographically smallest edge/label encoding over all
 * node-label-preserving permutations of the nodes. Because the miner only builds
 * connected patterns with at most a handful of edges, the node count is small
 * ({@code <= maxEdges + 1}), so exhaustive permutation enumeration is exact and fast.
 *
 * <p>The key fully characterizes labeled isomorphism (multi-edges and self-loops
 * included): two patterns share a key iff they are isomorphic as directed labeled
 * multigraphs.
 */
public final class PatternCodec {

    private PatternCodec() {
    }

    /** An edge expressed with host node ids. */
    public record RawEdge(String source, String target, String label) {
    }

    /** Result of canonicalization. */
    public static final class Canonical {
        private final String key;
        private final Map<Integer, String> mapping;

        Canonical(String key, Map<Integer, String> mapping) {
            this.key = key;
            this.mapping = mapping;
        }

        public String getKey() {
            return key;
        }

        /** canonical node index -> host node id */
        public Map<Integer, String> getMapping() {
            return mapping;
        }
    }

    /**
     * @param nodes      host node ids in discovery order (unique)
     * @param edges      edges whose endpoints are contained in {@code nodes}
     * @param nodeLabels host node id -> label
     */
    public static Canonical encode(List<String> nodes,
                                   List<RawEdge> edges,
                                   Map<String, String> nodeLabels) {
        int n = nodes.size();
        int[] pos = new int[n];
        for (int i = 0; i < n; i++) {
            pos[i] = i;
        }
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) {
            labels[i] = labelOf(nodeLabels, nodes.get(i));
        }
        // edges in terms of host positions
        int[][] edgePos = new int[edges.size()][2];
        String[] edgeLabels = new String[edges.size()];
        Map<String, Integer> indexOf = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            indexOf.put(nodes.get(i), i);
        }
        for (int i = 0; i < edges.size(); i++) {
            RawEdge e = edges.get(i);
            edgePos[i][0] = indexOf.get(e.source());
            edgePos[i][1] = indexOf.get(e.target());
            edgeLabels[i] = e.label() == null ? "" : e.label();
        }

        int[] bestPerm = null;
        String bestKey = null;
        // Enumerate permutations in lexicographic order, restricted to swaps within
        // equal node-label classes (label-preserving isomorphisms).
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) {
            perm[i] = i;
        }
        while (perm != null) {
            boolean labelPreserving = true;
            for (int j = 0; j < n; j++) {
                if (!labels[j].equals(labels[perm[j]])) {
                    labelPreserving = false;
                    break;
                }
            }
            if (labelPreserving) {
                String key = encodeWithPermutation(perm, labels, edgePos, edgeLabels);
                if (bestKey == null || key.compareTo(bestKey) < 0) {
                    bestKey = key;
                    bestPerm = perm.clone();
                }
            }
            perm = nextPermutation(perm);
        }

        Map<Integer, String> mapping = new LinkedHashMap<>();
        for (int canonical = 0; canonical < n; canonical++) {
            mapping.put(canonical, nodes.get(bestPerm[canonical]));
        }
        return new Canonical(bestKey, Map.copyOf(mapping));
    }

    private static String labelOf(Map<String, String> nodeLabels, String nodeId) {
        String l = nodeLabels.get(nodeId);
        return l == null ? "" : l;
    }

    private static String encodeWithPermutation(int[] perm,
                                                String[] nodeLabels,
                                                int[][] edges,
                                                String[] edgeLabels) {
        int n = nodeLabels.length;
        // inverse: host position -> canonical position
        int[] inv = new int[n];
        for (int canonical = 0; canonical < n; canonical++) {
            inv[perm[canonical]] = canonical;
        }
        StringBuilder sb = new StringBuilder("n:");
        for (int canonical = 0; canonical < n; canonical++) {
            if (canonical > 0) {
                sb.append(',');
            }
            sb.append(nodeLabels[perm[canonical]]);
        }
        List<String> parts = new ArrayList<>(edges.length);
        for (int i = 0; i < edges.length; i++) {
            parts.add(inv[edges[i][0]] + "->" + inv[edges[i][1]] + ":" + edgeLabels[i]);
        }
        parts.sort(Comparator.naturalOrder());
        sb.append("|e:");
        sb.append(String.join(",", parts));
        return sb.toString();
    }

    /** Next lexicographic permutation, or {@code null} if the current one is the last. */
    static int[] nextPermutation(int[] a) {
        int i = a.length - 2;
        while (i >= 0 && a[i] >= a[i + 1]) {
            i--;
        }
        if (i < 0) {
            return null;
        }
        int j = a.length - 1;
        while (a[j] <= a[i]) {
            j--;
        }
        int tmp = a[i];
        a[i] = a[j];
        a[j] = tmp;
        for (int l = i + 1, r = a.length - 1; l < r; l++, r--) {
            tmp = a[l];
            a[l] = a[r];
            a[r] = tmp;
        }
        return a;
    }
}
