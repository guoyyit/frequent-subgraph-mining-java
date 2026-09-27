package com.github.guoyyit.frequentsubgraphminingjava.durability;

import com.github.guoyyit.frequentsubgraphminingjava.model.Graph;

import java.util.Map;

/**
 * Payload of a durable snapshot: the committed graph collection and the WAL sequence
 * up to which the snapshot is consistent.
 */
public record SnapshotData(long lastSequence, Map<String, Graph> graphs) {
}
