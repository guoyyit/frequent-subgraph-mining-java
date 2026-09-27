package com.github.guoyyit.frequentsubgraphminingjava.web;

import com.github.guoyyit.frequentsubgraphminingjava.dto.EdgeDto;
import com.github.guoyyit.frequentsubgraphminingjava.dto.EmbeddingDto;
import com.github.guoyyit.frequentsubgraphminingjava.dto.GraphDto;
import com.github.guoyyit.frequentsubgraphminingjava.dto.MiningQueryDto;
import com.github.guoyyit.frequentsubgraphminingjava.dto.MiningResultDto;
import com.github.guoyyit.frequentsubgraphminingjava.dto.PatternMatchDto;
import com.github.guoyyit.frequentsubgraphminingjava.model.Embedding;
import com.github.guoyyit.frequentsubgraphminingjava.model.Graph;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningBudget;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningConfig;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Conversions between REST DTOs and the domain model. */
final class DtoMapper {

    private static final int DEFAULT_MAX_EDGES = 5;
    private static final int DEFAULT_MIN_SUPPORT = 2;
    private static final int DEFAULT_MAX_RESULTS = 10_000;
    private static final int DEFAULT_MAX_CANDIDATES = 1_000_000;
    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;

    private DtoMapper() {
    }

    static Graph toGraph(GraphDto dto) {
        if (dto.id() == null || dto.id().isBlank()) {
            throw new IllegalArgumentException("graph id is required");
        }
        Map<String, String> nodes = dto.nodeLabels() == null
                ? Map.of() : new LinkedHashMap<>(dto.nodeLabels());
        List<Graph.Edge> edges = new ArrayList<>();
        if (dto.edges() != null) {
            for (EdgeDto e : dto.edges()) {
                if (e.source() == null || e.target() == null) {
                    throw new IllegalArgumentException("edge source and target are required");
                }
                edges.add(new Graph.Edge(e.source(), e.target(),
                        e.label() == null ? "" : e.label()));
            }
        }
        return new Graph(dto.id(), nodes, edges);
    }

    static MiningConfig toConfig(MiningQueryDto dto) {
        if (dto == null) {
            return new MiningConfig(DEFAULT_MIN_SUPPORT, DEFAULT_MAX_EDGES, Set.of(), Set.of());
        }
        int minSupport = dto.minSupport() == null ? DEFAULT_MIN_SUPPORT : dto.minSupport();
        int maxEdges = dto.maxEdges() == null ? DEFAULT_MAX_EDGES : dto.maxEdges();
        if (minSupport < 1) {
            throw new IllegalArgumentException("minSupport must be >= 1");
        }
        if (maxEdges < 1) {
            throw new IllegalArgumentException("maxEdges must be >= 1");
        }
        return new MiningConfig(minSupport, maxEdges,
                dto.allowedNodeLabels() == null ? Set.of() : Set.copyOf(dto.allowedNodeLabels()),
                dto.allowedEdgeLabels() == null ? Set.of() : Set.copyOf(dto.allowedEdgeLabels()));
    }

    static MiningBudget toBudget(MiningQueryDto dto) {
        if (dto == null) {
            return new MiningBudget(DEFAULT_MAX_RESULTS, DEFAULT_MAX_CANDIDATES, DEFAULT_TIMEOUT_MILLIS);
        }
        return new MiningBudget(
                dto.maxResultPatterns() == null ? DEFAULT_MAX_RESULTS : dto.maxResultPatterns(),
                dto.maxCandidates() == null ? DEFAULT_MAX_CANDIDATES : dto.maxCandidates(),
                dto.timeoutMillis() == null ? DEFAULT_TIMEOUT_MILLIS : dto.timeoutMillis());
    }

    static MiningResultDto toDto(MiningResult result, long version) {
        List<PatternMatchDto> matches = new ArrayList<>();
        for (MiningResult.PatternMatch match : result.patterns()) {
            List<EmbeddingDto> embeddings = new ArrayList<>();
            for (Embedding embedding : match.embeddings()) {
                Map<String, String> mapping = new TreeMap<>();
                embedding.nodeMapping().forEach((k, v) -> mapping.put(String.valueOf(k), v));
                embeddings.add(new EmbeddingDto(embedding.graphId(), Map.copyOf(mapping)));
            }
            matches.add(new PatternMatchDto(
                    match.pattern().getCanonicalKey(),
                    match.pattern().getNodeCount(),
                    match.pattern().getEdgeCount(),
                    match.support(),
                    List.copyOf(embeddings)));
        }
        return new MiningResultDto(version, result.totalGraphs(), result.truncated(),
                result.limitReason(), result.scannedCandidates(), result.elapsedMillis(),
                List.copyOf(matches));
    }
}
