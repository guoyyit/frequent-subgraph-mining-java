package com.github.guoyyit.frequentsubgraphminingjava.durability;

import com.github.guoyyit.frequentsubgraphminingjava.model.Graph;
import com.github.guoyyit.frequentsubgraphminingjava.model.MutationType;

/**
 * One committed graph mutation in the WAL. {@code graph} is present for UPSERT and
 * {@code null} for DELETE.
 */
public record DurableEvent(long sequence,
                           MutationType type,
                           String graphId,
                           long timestampMillis,
                           Graph graph) {
}
