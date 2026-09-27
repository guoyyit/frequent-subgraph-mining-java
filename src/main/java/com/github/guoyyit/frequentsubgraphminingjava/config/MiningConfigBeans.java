package com.github.guoyyit.frequentsubgraphminingjava.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.frequentsubgraphminingjava.service.DefaultMiningService;
import com.github.guoyyit.frequentsubgraphminingjava.service.MiningService;
import com.github.guoyyit.frequentsubgraphminingjava.store.FilePersistenceStore;
import com.github.guoyyit.frequentsubgraphminingjava.store.PersistenceStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 挖掘服务相关 Bean 装配。
 */
@Configuration
@EnableConfigurationProperties(MiningProperties.class)
public class MiningConfigBeans {

    @Bean
    public PersistenceStore persistenceStore(MiningProperties properties, ObjectMapper objectMapper) {
        return new FilePersistenceStore(Path.of(properties.getStorageDir()), objectMapper);
    }

    @Bean
    public MiningService miningService(PersistenceStore persistenceStore, MiningProperties properties) {
        return new DefaultMiningService(persistenceStore, properties);
    }
}
