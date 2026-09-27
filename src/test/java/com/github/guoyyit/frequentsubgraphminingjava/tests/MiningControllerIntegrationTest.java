package com.github.guoyyit.frequentsubgraphminingjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP coverage against the real Spring stack with a temp data directory:
 * upsert two graphs sharing a motif, mine it, modify one graph, verify the support
 * drops, delete, and check request validation.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MiningControllerIntegrationTest {

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("fsm.data-dir", () -> tempDataDir.toString());
        registry.add("fsm.snapshot-every", () -> "0");
    }

    @Autowired
    private MockMvc mvc;

    private String graph(String id, String edgeLabel) {
        return "{\"id\":\"" + id + "\",\"nodeLabels\":{\"a\":\"A\",\"b\":\"B\"},"
                + "\"edges\":[{\"source\":\"a\",\"target\":\"b\",\"label\":\"" + edgeLabel + "\"}]}";
    }

    @Test
    void upsertMineModifyDeleteOverHttp() throws Exception {
        try {
            mvc.perform(post("/api/graphs").contentType(MediaType.APPLICATION_JSON)
                    .content(graph("g1", "knows"))).andExpect(status().isNoContent());
            mvc.perform(post("/api/graphs").contentType(MediaType.APPLICATION_JSON)
                    .content(graph("g2", "knows"))).andExpect(status().isNoContent());

            // A,B node labels + the shared knows edge => 3 frequent patterns at support 2.
            mvc.perform(post("/api/mine").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"minSupport\":2,\"maxEdges\":3}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(2))
                    .andExpect(jsonPath("$.totalGraphs").value(2))
                    .andExpect(jsonPath("$.truncated").value(false))
                    .andExpect(jsonPath("$.patterns.length()").value(3));

            // Modify g2: the "knows" edge motif is no longer shared; only A,B remain.
            mvc.perform(post("/api/graphs").contentType(MediaType.APPLICATION_JSON)
                    .content(graph("g2", "likes"))).andExpect(status().isNoContent());
            mvc.perform(post("/api/mine").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"minSupport\":2,\"maxEdges\":3}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(3))
                    .andExpect(jsonPath("$.patterns.length()").value(2));

            // Embeddings map canonical node index -> host node id.
            mvc.perform(post("/api/mine").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"minSupport\":1,\"maxEdges\":1}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.patterns[0].embeddings[0].nodeMapping.0").exists());
        } finally {
            mvc.perform(delete("/api/graphs/g1")).andExpect(status().isNoContent());
            mvc.perform(delete("/api/graphs/g2")).andExpect(status().isNoContent());
        }
    }

    @Test
    void invalidRequestsAreRejectedWith400() throws Exception {
        mvc.perform(post("/api/graphs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"\"}")).andExpect(status().isBadRequest());

        mvc.perform(post("/api/mine").contentType(MediaType.APPLICATION_JSON)
                .content("{\"minSupport\":0}")).andExpect(status().isBadRequest());

        mvc.perform(post("/api/mine").contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxEdges\":0}")).andExpect(status().isBadRequest());

        mvc.perform(post("/api/mine").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minSupport\":1,\"maxEdges\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").exists())
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.patterns").isArray());
    }
}
