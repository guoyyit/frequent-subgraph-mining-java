package com.github.guoyyit.frequentsubgraphminingjava.mining;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 小型带标签有向图（允许平行边）的规范同构编码器。
 *
 * <p>通过枚举顶点排列求字典序最小的规范表示：同构的模式（标签与有向边连接
 * 关系一致）必定得到相同的规范字符串，与节点编号、边的录入顺序无关。
 * 规范表示同时给出所有取得最小值的排列（即自同构映射），供嵌入枚举使用。
 *
 * <p>顶点排列数为阶乘复杂度，模式顶点数 = 边数 + 1（连通模式），
 * 因此最大模式边数上限同时限制了这里的复杂度。
 */
public final class Canonicalizer {

    /** 模式节点在嵌入映射中的统一 id 前缀：p0、p1…… */
    public static String patternNodeId(int canonicalIndex) {
        return "p" + canonicalIndex;
    }

    private Canonicalizer() {
    }

    /** 输入图的一条边：端点为局部顶点下标。 */
    public record InputEdge(int fromLocal, int toLocal, String label) {
    }

    /** 规范编码结果。 */
    public static final class Result {
        private final CanonicalCode code;
        /** 每个元素是一个获胜排列：order[规范下标] = 局部下标。 */
        private final List<int[]> winningOrders;

        Result(CanonicalCode code, List<int[]> winningOrders) {
            this.code = code;
            this.winningOrders = winningOrders;
        }

        public CanonicalCode getCode() {
            return code;
        }

        public List<int[]> getWinningOrders() {
            return winningOrders;
        }
    }

    /**
     * 计算规范编码。
     *
     * @param localNodeLabels 局部下标 -> 节点标签，长度为顶点数
     * @param inputEdges      边集合（端点使用局部下标）
     */
    public static Result canonicalize(List<String> localNodeLabels, List<InputEdge> inputEdges) {
        int n = localNodeLabels.size();
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) {
            perm[i] = i;
        }

        List<Object> bestKey = null;
        List<int[]> winners = new ArrayList<>();

        // 堆算法枚举全部顶点排列
        int[] c = new int[n];
        int[] current = perm.clone();
        while (true) {
            List<Object> key = keyOf(current, localNodeLabels, inputEdges);
            int cmp = bestKey == null ? -1 : compareKey(key, bestKey);
            if (cmp < 0) {
                bestKey = key;
                winners.clear();
                winners.add(current.clone());
            } else if (cmp == 0) {
                winners.add(current.clone());
            }
            int i = 0;
            while (i < n && c[i] >= i + 1) {
                c[i] = 0;
                i++;
            }
            if (i >= n) {
                break;
            }
            int j = (i % 2 == 0) ? 0 : c[i];
            int tmp = current[j];
            current[j] = current[i];
            current[i] = tmp;
            c[i]++;
        }

        CanonicalCode canonicalCode = toCanonicalCode(bestKey, n, inputEdges, winners.get(0));
        return new Result(canonicalCode, winners);
    }

    /**
     * 给定一个排列（perm[规范下标] = 局部下标），构造可比的规范键：
     * 节点标签序列 + 边数 + 按（规范起点, 规范终点, 标签）排序后的边序列。
     * 有向边方向保持不变。
     */
    private static List<Object> keyOf(int[] perm, List<String> labels, List<InputEdge> edges) {
        int n = perm.length;
        int[] localToCanonical = new int[n];
        for (int canonical = 0; canonical < n; canonical++) {
            localToCanonical[perm[canonical]] = canonical;
        }

        List<List<Object>> edgeKeys = new ArrayList<>(edges.size());
        for (InputEdge e : edges) {
            int cf = localToCanonical[e.fromLocal()];
            int ct = localToCanonical[e.toLocal()];
            List<Object> ek = new ArrayList<>(4);
            ek.add(cf);
            ek.add(ct);
            ek.add(e.label());
            edgeKeys.add(ek);
        }
        edgeKeys.sort(Canonicalizer::compareKey);

        List<Object> key = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            key.add(labels.get(perm[i]));
        }
        key.add(Integer.MIN_VALUE); // 节点段结束标记，保证前缀安全
        key.add(edges.size());
        for (List<Object> ek : edgeKeys) {
            key.addAll(ek);
        }
        return key;
    }

    @SuppressWarnings("unchecked")
    private static int compareKey(List<Object> a, List<Object> b) {
        int len = Math.min(a.size(), b.size());
        for (int i = 0; i < len; i++) {
            int cmp = compareToken(a.get(i), b.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    private static int compareToken(Object x, Object y) {
        // 类型顺序：段标记(Integer.MIN_VALUE) < 整数下标 < 字符串标签
        if (x instanceof Integer xi && y instanceof Integer yi) {
            return Integer.compare(xi, yi);
        }
        if (x instanceof String xs && y instanceof String ys) {
            return xs.compareTo(ys);
        }
        if (x instanceof Integer && y instanceof String) {
            return -1;
        }
        return 1;
    }

    private static CanonicalCode toCanonicalCode(List<Object> bestKey, int n,
                                                 List<InputEdge> edges, int[] firstWinner) {
        List<String> nodeLabels = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            nodeLabels.add((String) bestKey.get(i));
        }

        int[] localToCanonical = new int[n];
        for (int canonical = 0; canonical < n; canonical++) {
            localToCanonical[firstWinner[canonical]] = canonical;
        }
        List<CanonicalEdge> canonicalEdges = new ArrayList<>(edges.size());
        for (InputEdge e : edges) {
            canonicalEdges.add(new CanonicalEdge(
                    localToCanonical[e.fromLocal()],
                    localToCanonical[e.toLocal()],
                    e.label()));
        }
        canonicalEdges.sort((a, b) -> {
            if (a.from() != b.from()) {
                return Integer.compare(a.from(), b.from());
            }
            if (a.to() != b.to()) {
                return Integer.compare(a.to(), b.to());
            }
            return a.label().compareTo(b.label());
        });

        return new CanonicalCode(render(nodeLabels, canonicalEdges), nodeLabels, canonicalEdges);
    }

    /**
     * 规范字符串形式：v[标签,...]e[起点-标签-终点;...]。
     * 标签中的反斜杠与分隔符做转义，保证不同模式不会因分隔符碰撞而冲突。
     */
    public static String render(List<String> nodeLabels, List<CanonicalEdge> edges) {
        StringBuilder sb = new StringBuilder("v[");
        for (int i = 0; i < nodeLabels.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(nodeLabels.get(i)));
        }
        sb.append("]e[");
        for (int i = 0; i < edges.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            CanonicalEdge e = edges.get(i);
            sb.append(e.from()).append('-').append(escape(e.label())).append('-').append(e.to());
        }
        sb.append(']');
        return sb.toString();
    }

    private static String escape(String label) {
        StringBuilder sb = new StringBuilder(label.length() + 2);
        for (int i = 0; i < label.length(); i++) {
            char ch = label.charAt(i);
            if (ch == '\\' || ch == ',' || ch == ';' || ch == '[' || ch == ']' || ch == '-') {
                sb.append('\\');
            }
            sb.append(ch);
        }
        return sb.toString();
    }
}
