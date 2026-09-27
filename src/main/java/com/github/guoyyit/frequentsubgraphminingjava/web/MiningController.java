package com.github.guoyyit.frequentsubgraphminingjava.web;

import com.github.guoyyit.frequentsubgraphminingjava.dto.GraphDto;
import com.github.guoyyit.frequentsubgraphminingjava.dto.MiningQueryDto;
import com.github.guoyyit.frequentsubgraphminingjava.dto.MiningResultDto;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningResult;
import com.github.guoyyit.frequentsubgraphminingjava.service.FrequentSubgraphService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST API:
 * <ul>
 *   <li>{@code POST /api/graphs} — add or replace a graph (UPSERT)</li>
 *   <li>{@code DELETE /api/graphs/{id}} — remove a graph</li>
 *   <li>{@code POST /api/mine} — run a frequent-pattern query against a committed snapshot</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class MiningController {

    private final FrequentSubgraphService service;

    public MiningController(FrequentSubgraphService service) {
        this.service = service;
    }

    @PostMapping("/graphs")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upsertGraph(@RequestBody GraphDto dto) {
        service.upsertGraph(DtoMapper.toGraph(dto));
    }

    @DeleteMapping("/graphs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGraph(@PathVariable String id) {
        service.deleteGraph(id);
    }

    @PostMapping("/mine")
    public MiningResultDto mine(@RequestBody(required = false) MiningQueryDto query) {
        MiningResult result = service.query(DtoMapper.toConfig(query), DtoMapper.toBudget(query));
        return DtoMapper.toDto(result, service.currentVersion());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage() == null ? "invalid request" : e.getMessage());
    }
}
