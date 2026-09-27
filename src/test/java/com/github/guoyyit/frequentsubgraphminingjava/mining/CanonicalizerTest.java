package com.github.guoyyit.frequentsubgraphminingjava.mining;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CanonicalizerTest {

    private Canonicalizer.Result encode(List<String> labels,
                                        int[][] edges) {
        List<Canonicalizer.InputEdge> es = java.util.Arrays.stream(edges)
                .map(e -> new Canonicalizer.InputEdge(e[0], e[1], "e" + e[2]))
                .toList();
        return Canonicalizer.canonicalize(labels, es);
    }

    @Test
    void isomorphicRelabelingGivesSameCode() {
        // 同一张有向图 A->B->C，第二份数据只是局部下标按逆序录入
        var r1 = encode(List.of("A", "B", "C"), new int[][]{{0, 1, 1}, {1, 2, 1}});
        var r2 = encode(List.of("C", "B", "A"), new int[][]{{1, 0, 1}, {2, 1, 1}});
        assertEquals(r1.getCode().getCode(), r2.getCode().getCode());
    }

    @Test
    void differentLabelSequenceGivesDifferentCode() {
        var r1 = encode(List.of("A", "B"), new int[][]{{0, 1, 1}});
        var r2 = encode(List.of("B", "A"), new int[][]{{0, 1, 1}});
        // A->B 与 B->A 方向/标签顺序不同
        assertNotEquals(r1.getCode().getCode(), r2.getCode().getCode());
    }

    @Test
    void edgeDirectionMatters() {
        var r1 = encode(List.of("A", "B"), new int[][]{{0, 1, 1}});
        var r2 = encode(List.of("A", "B"), new int[][]{{1, 0, 1}});
        assertNotEquals(r1.getCode().getCode(), r2.getCode().getCode());
    }

    @Test
    void triangleSymmetryWinsMultipleOrders() {
        var r = encode(List.of("X", "X", "X"),
                new int[][]{{0, 1, 1}, {1, 2, 1}, {0, 2, 1}});
        assertEquals(3, r.getCode().getNodeLabels().size());
        assertEquals(3, r.getCode().getEdgeCount());
    }
}
