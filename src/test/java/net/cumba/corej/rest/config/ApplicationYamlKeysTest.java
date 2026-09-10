package net.cumba.corej.rest.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The {@code corej:} section of {@code application.yaml} is the <b>only</b> place the service names
 * its configuration keys to an operator, and most of them are shipped commented out as a copy-paste
 * template. {@code @ConfigurationProperties("corej")} binds with
 * {@code ignoreUnknownFields = true}, so a key the documentation names but {@link CorejProperties}
 * no longer has binds to <b>nothing</b> — no error, no warning, no failed startup — and the
 * operator silently gets the defaults they thought they were overriding.
 *
 * <p>
 * That is exactly what review finding MS-1 was: a rename of {@code target-dir} →
 * {@code target-store} and {@code overwrite} → {@code refresh} swept the Java but not the yaml,
 * re-opening the F2 store inversion for anyone who followed the shipped comments. This test pins
 * the key <i>names</i>, which is the half a doc block can be checked on.
 * </p>
 *
 * <p>
 * ⚠ Deliberately a <b>name-set</b> check, not a path check: the commented template and the live
 * keys interleave at different indentation, so reconstructing full paths from comments would be
 * guesswork. Every documented key must be a property name {@code CorejProperties} actually declares
 * somewhere; a rename therefore reds this test until the yaml is swept too.
 * </p>
 */
class ApplicationYamlKeysTest
{

    /**
     * A yaml key line: a lower-kebab name, a colon, and at most a single-token value plus an
     * optional trailing comment. Prose (which is what the rest of the block is) does not match — it
     * either starts with a capital or has no colon glued to its first word.
     */
    private static final Pattern KEY_LINE = Pattern
            .compile("^([a-z][a-z0-9-]*):(?:\\s+(\\S+))?\\s*(?:#.*)?$");

    @Test
    void everyDocumentedCorejKeyBindsToARealProperty() throws IOException
    {
        List<String> documented = documentedCorejKeys();
        // Non-vacuity: a parser that silently matched nothing would pass forever.
        assertTrue(documented.size() >= 10,
                "expected the corej: section to document at least 10 keys, found " + documented);

        Set<String> bindable = bindableNames(CorejProperties.class, new HashSet<>());
        List<String> unbound = new ArrayList<>();
        for (String key : documented)
        {
            if (!bindable.contains(key))
            {
                unbound.add(key);
            }
        }
        assertTrue(unbound.isEmpty(),
                "application.yaml documents corej keys that CorejProperties cannot bind: " + unbound
                        + " — the yaml was not swept after a rename. Bindable names: " + bindable);
    }


    /** Guards the parser itself: the retired names must NOT be considered bindable. */
    @Test
    void retiredSeedNamesAreNotBindable()
    {
        Set<String> bindable = bindableNames(CorejProperties.class, new HashSet<>());
        assertFalse(bindable.contains("target-dir"), "target-dir was renamed to target-store");
        assertFalse(bindable.contains("overwrite"), "overwrite was renamed to refresh");
        assertTrue(bindable.contains("target-store"));
        assertTrue(bindable.contains("refresh"));
        assertTrue(bindable.contains("from-api"));
    }


    /**
     * Every key name named in the {@code corej:} section of the packaged {@code application.yaml},
     * live keys and commented-out template lines alike. A commented line contributes after its
     * leading {@code #} is stripped; continuation lines (which carry a second {@code #}) do not.
     */
    private static List<String> documentedCorejKeys() throws IOException
    {
        List<String> lines = readApplicationYaml();
        List<String> keys = new ArrayList<>();
        boolean inCorej = false;
        for (String raw : lines)
        {
            if (!raw.isEmpty() && !Character.isWhitespace(raw.charAt(0)) && raw.charAt(0) != '#')
            {
                inCorej = raw.startsWith("corej:");
                continue;
            }
            if (!inCorej)
            {
                continue;
            }
            String content = raw.strip();
            if (content.startsWith("#"))
            {
                content = content.substring(1).strip();
            }
            Matcher matcher = KEY_LINE.matcher(content);
            if (matcher.matches())
            {
                keys.add(matcher.group(1));
            }
        }
        return keys;
    }


    private static List<String> readApplicationYaml() throws IOException
    {
        try (InputStream in = ApplicationYamlKeysTest.class
                .getResourceAsStream("/application.yaml"))
        {
            assertTrue(in != null, "application.yaml must be on the test classpath");
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1));
        }
    }


    /**
     * Relaxed (kebab-case) names of every property {@link CorejProperties} exposes, at any depth —
     * both leaves and the group names that hold them.
     */
    private static Set<String> bindableNames(Class<?> aType, Set<Class<?>> aVisited)
    {
        Set<String> names = new LinkedHashSet<>();
        if (!aVisited.add(aType))
        {
            return names;
        }
        for (Method method : aType.getMethods())
        {
            if (method.getParameterCount() != 0 || method.getDeclaringClass() == Object.class)
            {
                continue;
            }
            String name = method.getName();
            String property;
            if (name.startsWith("get") && name.length() > 3)
            {
                property = name.substring(3);
            }
            else if (name.startsWith("is") && name.length() > 2)
            {
                property = name.substring(2);
            }
            else
            {
                continue;
            }
            names.add(kebab(property));
            Class<?> returned = method.getReturnType();
            if (isNestedConfigurationType(returned))
            {
                names.addAll(bindableNames(returned, aVisited));
            }
        }
        return names;
    }


    private static boolean isNestedConfigurationType(Class<?> aType)
    {
        return aType.getEnclosingClass() != null
                && aType.getName().startsWith(CorejProperties.class.getName());
    }


    private static String kebab(String aCamel)
    {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < aCamel.length(); i++)
        {
            char c = aCamel.charAt(i);
            if (Character.isUpperCase(c))
            {
                if (i > 0)
                {
                    out.append('-');
                }
                out.append(Character.toLowerCase(c));
            }
            else
            {
                out.append(c);
            }
        }
        return out.toString();
    }
}
