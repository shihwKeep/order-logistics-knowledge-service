package com.xjjk.knowledge.publication.release;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class ReleaseConfigurationTest {
    @Test
    void releaseWorkerIsEnabledByDefault() throws Exception {
        var sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));

        assertThat(sources)
                .anySatisfy(source -> assertThat(source.getProperty("knowledge.release.enabled"))
                        .isEqualTo(true));
    }
}
