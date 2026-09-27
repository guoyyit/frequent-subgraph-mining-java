package com.github.guoyyit.frequentsubgraphminingjava.mining;

/**
 * 规范模式中的一条边，端点为规范节点下标。
 */
public record CanonicalEdge(int from, int to, String label) {
}
