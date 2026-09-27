package com.github.guoyyit.frequentsubgraphminingjava.durability;

import com.github.guoyyit.frequentsubgraphminingjava.model.Graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal deterministic JSON codec for durable records and graph snapshots.
 *
 * <p>No external JSON library is required; keys are always emitted in a fixed order
 * and graph collections are serialized in graph-id order, so identical state always
 * produces identical bytes (supporting deterministic regression comparison).
 */
public final class GraphCodec {

    private GraphCodec() {
    }

    // ---------- graph ----------

    public static String graphToJson(Graph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"id\":").append(quote(graph.getId()));
        sb.append(",\"nodes\":{");
        List<String> nodeIds = new ArrayList<>(graph.getNodeLabels().keySet());
        java.util.Collections.sort(nodeIds);
        boolean first = true;
        for (String nodeId : nodeIds) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(nodeId)).append(':').append(quote(graph.getNodeLabels().get(nodeId)));
        }
        sb.append("},\"edges\":[");
        for (int i = 0; i < graph.getEdges().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Graph.Edge e = graph.getEdges().get(i);
            sb.append('{')
                    .append("\"s\":").append(quote(e.source()))
                    .append(",\"t\":").append(quote(e.target()))
                    .append(",\"l\":").append(quote(e.label()))
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    public static Graph graphFromJson(String json) {
        Parser p = new Parser(json);
        p.expect('{');
        String id = null;
        Map<String, String> nodes = new LinkedHashMap<>();
        List<Graph.Edge> edges = new ArrayList<>();
        while (true) {
            String key = p.readString();
            p.expect(':');
            switch (key) {
                case "id" -> id = p.readString();
                case "nodes" -> {
                    p.expect('{');
                    if (!p.peek('}')) {
                        while (true) {
                            String nid = p.readString();
                            p.expect(':');
                            String label = p.readString();
                            nodes.put(nid, label);
                            if (p.consume(',')) {
                                continue;
                            }
                            break;
                        }
                    }
                    p.expect('}');
                }
                case "edges" -> {
                    p.expect('[');
                    if (!p.peek(']')) {
                        while (true) {
                            p.expect('{');
                            String s = null, t = null, l = null;
                            while (true) {
                                String ek = p.readString();
                                p.expect(':');
                                String ev = p.readString();
                                switch (ek) {
                                    case "s" -> s = ev;
                                    case "t" -> t = ev;
                                    case "l" -> l = ev;
                                    default -> { }
                                }
                                if (p.consume(',')) {
                                    continue;
                                }
                                break;
                            }
                            p.expect('}');
                            edges.add(new Graph.Edge(s, t, l));
                            if (p.consume(',')) {
                                continue;
                            }
                            break;
                        }
                    }
                    p.expect(']');
                }
                default -> throw new IllegalArgumentException("unknown graph key: " + key);
            }
            if (p.consume(',')) {
                continue;
            }
            break;
        }
        p.expect('}');
        p.end();
        return new Graph(id, nodes, edges);
    }

    // ---------- WAL event ----------

    /** Wire form: graph carried as a raw JSON fragment (null for DELETE). */
    public record WireEvent(long sequence, String op, String graphId,
                            long timestampMillis, String graphJson) {
    }

    public static String eventToJson(WireEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"seq\":").append(event.sequence());
        sb.append(",\"op\":").append(quote(event.op()));
        sb.append(",\"id\":").append(quote(event.graphId()));
        sb.append(",\"ts\":").append(event.timestampMillis());
        sb.append(",\"graph\":").append(event.graphJson() == null ? "null" : event.graphJson());
        sb.append('}');
        return sb.toString();
    }

    public static WireEvent eventFromJson(String json) {
        Parser p = new Parser(json);
        p.expect('{');
        long seq = 0;
        String op = null;
        String id = null;
        long ts = 0;
        String graphJson = null;
        while (true) {
            String key = p.readString();
            p.expect(':');
            switch (key) {
                case "seq" -> seq = p.readLong();
                case "op" -> op = p.readString();
                case "id" -> id = p.readString();
                case "ts" -> ts = p.readLong();
                case "graph" -> {
                    if (p.consumeLiteral("null")) {
                        graphJson = null;
                    } else {
                        int start = p.position();
                        p.skipValue();
                        graphJson = json.substring(start, p.position());
                    }
                }
                default -> throw new IllegalArgumentException("unknown event key: " + key);
            }
            if (p.consume(',')) {
                continue;
            }
            break;
        }
        p.expect('}');
        p.end();
        return new WireEvent(seq, op, id, ts, graphJson);
    }

    // ---------- snapshot: ordered graph list ----------

    public static String snapshotToJson(long lastSequence, Map<String, Graph> graphs) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"lastSeq\":").append(lastSequence);
        sb.append(",\"graphs\":{");
        List<String> ids = new ArrayList<>(graphs.keySet());
        java.util.Collections.sort(ids);
        boolean first = true;
        for (String id : ids) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(id)).append(':').append(graphToJson(graphs.get(id)));
        }
        sb.append("}}");
        return sb.toString();
    }

    public static SnapshotParsed snapshotFromJson(String json) {
        Parser p = new Parser(json);
        p.expect('{');
        long lastSeq = 0;
        Map<String, Graph> graphs = new TreeMap<>();
        while (true) {
            String key = p.readString();
            p.expect(':');
            switch (key) {
                case "lastSeq" -> lastSeq = p.readLong();
                case "graphs" -> {
                    p.expect('{');
                    if (!p.peek('}')) {
                        while (true) {
                            String gid = p.readString();
                            p.expect(':');
                            int start = p.position();
                            p.skipValue();
                            Graph graph = graphFromJson(json.substring(start, p.position()));
                            graphs.put(gid, graph);
                            if (p.consume(',')) {
                                continue;
                            }
                            break;
                        }
                    }
                    p.expect('}');
                }
                default -> throw new IllegalArgumentException("unknown snapshot key: " + key);
            }
            if (p.consume(',')) {
                continue;
            }
            break;
        }
        p.expect('}');
        p.end();
        return new SnapshotParsed(lastSeq, Map.copyOf(graphs));
    }

    public record SnapshotParsed(long lastSequence, Map<String, Graph> graphs) {
    }

    // ---------- primitives ----------

    static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** Small recursive-descent JSON reader supporting exactly the shapes above. */
    static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
            this.i = 0;
        }

        int position() {
            return i;
        }

        void end() {
            skipWs();
            if (i != s.length()) {
                throw new IllegalArgumentException("trailing content at " + i);
            }
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        void expect(char c) {
            skipWs();
            if (i >= s.length() || s.charAt(i) != c) {
                throw new IllegalArgumentException("expected '" + c + "' at " + i);
            }
            i++;
        }

        boolean peek(char c) {
            skipWs();
            return i < s.length() && s.charAt(i) == c;
        }

        boolean consume(char c) {
            skipWs();
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        boolean consumeLiteral(String literal) {
            skipWs();
            if (s.startsWith(literal, i)) {
                i += literal.length();
                return true;
            }
            return false;
        }

        String readString() {
            skipWs();
            if (i >= s.length() || s.charAt(i) != '"') {
                throw new IllegalArgumentException("expected string at " + i);
            }
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char esc = s.charAt(i++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            int code = Integer.parseInt(s.substring(i, i + 4), 16);
                            i += 4;
                            sb.append((char) code);
                        }
                        default -> throw new IllegalArgumentException("bad escape \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        long readLong() {
            skipWs();
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
                i++;
            }
            while (i < s.length() && Character.isDigit(s.charAt(i))) {
                i++;
            }
            return Long.parseLong(s.substring(start, i));
        }

        /** Advances past a single JSON value (object/array/string/number/literal). */
        void skipValue() {
            skipWs();
            char c = s.charAt(i);
            if (c == '"') {
                readString();
                return;
            }
            if (c == '{' || c == '[') {
                char open = c;
                char close = open == '{' ? '}' : ']';
                int depth = 0;
                boolean inString = false;
                while (i < s.length()) {
                    char ch = s.charAt(i++);
                    if (inString) {
                        if (ch == '\\') {
                            i++;
                        } else if (ch == '"') {
                            inString = false;
                        }
                        continue;
                    }
                    if (ch == '"') {
                        inString = true;
                    } else if (ch == open) {
                        depth++;
                    } else if (ch == close) {
                        depth--;
                        if (depth == 0) {
                            return;
                        }
                    }
                }
                throw new IllegalArgumentException("unterminated value");
            }
            while (i < s.length() && ",}]".indexOf(s.charAt(i)) < 0
                    && !Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
