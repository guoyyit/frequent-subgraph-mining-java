package com.github.guoyyit.frequentsubgraphminingjava.model;

/**
 * A canonical connected labeled pattern. Nodes are identified by their canonical
 * index (0..nodeCount-1); the key fully determines pattern isomorphism.
 */
public final class Pattern implements Comparable<Pattern> {

    private final String canonicalKey;
    private final int nodeCount;
    private final int edgeCount;

    public Pattern(String canonicalKey, int nodeCount, int edgeCount) {
        this.canonicalKey = canonicalKey;
        this.nodeCount = nodeCount;
        this.edgeCount = edgeCount;
    }

    public String getCanonicalKey() {
        return canonicalKey;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public int getEdgeCount() {
        return edgeCount;
    }

    @Override
    public int compareTo(Pattern other) {
        return this.canonicalKey.compareTo(other.canonicalKey);
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof Pattern p) && canonicalKey.equals(p.canonicalKey);
    }

    @Override
    public int hashCode() {
        return canonicalKey.hashCode();
    }

    @Override
    public String toString() {
        return canonicalKey;
    }
}
