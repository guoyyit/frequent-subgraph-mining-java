package com.github.guoyyit.frequentsubgraphminingjava.config;

import com.github.guoyyit.frequentsubgraphminingjava.service.FrequentSubgraphService;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(MiningProperties.class)
public class ServiceConfig {

    @Bean
    public FrequentSubgraphService frequentSubgraphService(MiningProperties properties) {
        FrequentSubgraphService service = new FrequentSubgraphService(
                Path.of(properties.getDataDir()), properties.getSnapshotEvery());
        service.start();
        return service;
    }
}
