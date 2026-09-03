package net.cumba.corej.rest.run;

import jakarta.annotation.PostConstruct;
import net.cumba.corej.core.metadata.DomainClassMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Forces the engine's static-initializer log sites to fire <em>once at REST startup</em>, outside
 * any run, so their one-time infrastructure warnings go to the server log only and are never
 * attributed to (or raced across) a check run.
 *
 * <p>
 * One engine class logs from a lazily-triggered static block / lazy singleton, and a second is
 * touched only so its class initialisation happens here rather than mid-run:
 * </p>
 * <ul>
 * <li>{@link DomainClassMap} — its {@code load()} (run on first
 * {@link DomainClassMap#getInstance()}) warns when the bundled map is missing or an external
 * override is unreadable, and logs an INFO when an override is merged.</li>
 * <li>{@code net.cumba.corej.core.gen.RuleGenerator} — class initialisation, kept in the warm-up so
 * its (currently silent) static state is established on the startup thread rather than on whichever
 * worker first touches the class. ⚑ Its one log site — the bundled {@code rules-templates.json}
 * load — was retired with that file by Fix #366.</li>
 * </ul>
 *
 * <p>
 * That warning would otherwise fire on whatever thread first triggers class-load — which, under
 * parallel load, could be an engine worker thread mid-run (with a capture sink bound),
 * mis-attributing the one-time warning to that run. Touching both classes here at startup makes
 * capture deterministic: the warm-up runs on the Spring startup thread with no sink bound, so the
 * warning reaches only the server log.
 * </p>
 *
 * <p>
 * ⚑ The {@code RuleGenerator} half is <b>vestigial</b> since Fix #366 deleted its one log site, and
 * is a candidate for removal together with the generator code. It is kept for now: removing it is
 * not that change's business, and the class may regain static state.
 * </p>
 */
@Component
public class EngineStaticInitWarmUp
{

    private static final Logger LOG = LoggerFactory.getLogger(EngineStaticInitWarmUp.class);

    /**
     * Fully-qualified name of the engine's rule generator. Referenced by name (rather than an
     * import) because the REST layer only needs to <em>trigger</em> its static initializer, not use
     * the type — and the no-arg constructor requires a {@code MetadataProvider} we do not have
     * here.
     */
    private static final String RULE_GENERATOR = "net.cumba.corej.core.gen.RuleGenerator";

    @PostConstruct
    void warmUp()
    {
        // Force DomainClassMap's lazy singleton (and its load-time warnings) now.
        DomainClassMap.getInstance();
        // Force RuleGenerator's class initialisation now, without constructing an instance.
        try
        {
            Class.forName(RULE_GENERATOR, true, getClass().getClassLoader());
        }
        catch (ClassNotFoundException e)
        {
            // Non-fatal: the warm-up is an isolation nicety, not a correctness requirement. If the
            // class is somehow absent, the engine would fail later for unrelated reasons anyway.
            LOG.warn("Could not warm up {} at startup: {}", RULE_GENERATOR, e.getMessage());
        }
    }
}
