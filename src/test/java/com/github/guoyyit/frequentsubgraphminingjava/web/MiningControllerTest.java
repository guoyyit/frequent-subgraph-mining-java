package com.github.guoyyit.frequentsubgraphminingjava.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.frequentsubgraphminingjava.config.MiningProperties;
import com.github.guoyyit.frequentsubgraphminingjava.service.DefaultMiningService;
import com.github.guoyyit.frequentsubgraphminingjava.service.MiningService;
import com.github.guoyyit.frequentsubgraphminingjava.store.FilePersistenceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MiningControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp(@TempDir Path dir) {
        MiningProperties properties = new MiningProperties();
        MiningService service = new DefaultMiningService(
                new FilePersistenceStore(dir, mapper), properties);
        mockMvc = MockMvcBuilders.standaloneSetup(new MiningController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private String addGraphPayload(String name) throws Exception {
        return mapper.writeValueAsString(Map.of(
                "operation", "ADD",
                "graphName", name,
                "nodes", List.of(
                        Map.of("id", "a", "label", "A"),
                        Map.of("id", "b", "label", "B")),
                "edges", List.of(Map.of("source", "a", "target", "b", "label", "r"))));
    }

    @Test
    void changeQueryVersionFlow() throws Exception {
        mockMvc.perform(post("/api/mining/graphs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addGraphPayload("g1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(get("/api/mining/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(post("/api/mining/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.graphCount").value(1))
                .andExpect(jsonPath("$.catalogVersion").value(1))
                .andExpect(jsonPath("$.patterns[?(@.edgeCount==1)].support").value(org.hamcrest.Matchers.hasItem(1)));
    }

    @Test
    void duplicateAddReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/mining/graphs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addGraphPayload("g1")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/mining/graphs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addGraphPayload("g1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void queryWithEmptyBodyUsesDefaults() throws Exception {
        mockMvc.perform(post("/api/mining/query"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETE"));
    }
}
