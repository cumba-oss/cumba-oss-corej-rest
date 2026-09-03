package net.cumba.corej.rest;

import static org.assertj.core.api.Assertions.assertThat;

import net.cumba.corej.rest.config.CorejProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Smoke test that the application context loads with the <em>shipped</em> {@code application.yaml}
 * (no property overrides). Guards against config-binding regressions — e.g. a
 * {@code corej.sessions} key present with no value, which makes Spring try (and fail) to convert
 * {@code String} to the nested {@code Sessions} type. The other tests all set
 * {@code corej.sessions.dir}, so they would not catch that.
 */
@SpringBootTest
class CorejRestApplicationTest
{

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsAndBindsShippedConfig()
    {
        CorejProperties props = context.getBean(CorejProperties.class);
        assertThat(props.getRuns().getMaxParallel()).isEqualTo(2);
        assertThat(props.getReports().getCache().getMaxEntries()).isEqualTo(64);
        assertThat(props.getSessions()).isNotNull();
    }
}
