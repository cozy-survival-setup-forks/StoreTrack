package dev.storetrack.safe;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks the values of a settings file after it has parsed: a text where a number belongs, a negative cooldown,
 * a material that does not exist. Each problem names the file and the key. Nothing is changed on disk.
 */
public final class Guard {

    public static final class Problem {
        public final String file;
        public final String key;
        public final String message;

        Problem(String file, String key, String message) {
            this.file = file;
            this.key = key;
            this.message = message;
        }

        @Override
        public String toString() {
            return file + ": '" + key + "' " + message;
        }
    }

    /** Extra, plugin specific rules. Only keys that are present are checked. */
    public static final class Rules {
        private final String file;
        private final YamlConfiguration disk;
        private final List<Problem> problems;

        Rules(String file, YamlConfiguration disk, List<Problem> problems) {
            this.file = file;
            this.disk = disk;
            this.problems = problems;
        }

        public void problem(String key, String message) {
            for (Problem p : problems)
                if (p.key.equals(key))
                    return;
            problems.add(new Problem(file, key, message));
        }

        /** A number from min to max (both included). */
        public void range(String key, double min, double max) {
            if (!disk.contains(key))
                return;
            Object v = disk.get(key);
            if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue())) {
                problem(key, "is " + show(v) + " but must be a number");
            } else if (n.doubleValue() < min || n.doubleValue() > max) {
                problem(key, "is " + show(v) + " but must be from " + trim(min) + " to " + trim(max));
            }
        }

        public void bool(String key) {
            if (disk.contains(key) && !(disk.get(key) instanceof Boolean))
                problem(key, "is " + show(disk.get(key)) + " but must be true or false");
        }

        /** One of these words, in any case. */
        public void oneOf(String key, String... allowed) {
            if (!disk.contains(key))
                return;
            Object v = disk.get(key);
            if (v != null && v.toString().isBlank())
                return;
            for (String a : allowed)
                if (a.equalsIgnoreCase(String.valueOf(v).trim()))
                    return;
            problem(key, "is " + show(v) + " but must be one of " + String.join(", ", allowed));
        }

        public void enumOf(String key, Class<? extends Enum<?>> type) {
            Enum<?>[] constants = type.getEnumConstants();
            String[] names = new String[constants.length];
            for (int i = 0; i < names.length; i++)
                names[i] = constants[i].name();
            oneOf(key, names);
        }

        public void material(String key) {
            if (!disk.contains(key))
                return;
            Object v = disk.get(key);
            if (v == null || v.toString().isBlank())
                return;
            if (!isMaterial(v.toString()))
                problem(key, "is " + show(v) + " which is not an item or block");
        }

        public void materials(String key) {
            if (!disk.contains(key))
                return;
            Object v = disk.get(key);
            if (!(v instanceof List<?> list))
                return;
            for (Object o : list)
                if (o != null && !o.toString().startsWith("#") && !isMaterial(o.toString()))
                    problem(key, "has " + show(o) + " which is not an item or block");
        }

        /** A time such as 90, 30s, 10m, 2h, 1d or 1h30m. */
        public void duration(String key) {
            if (!disk.contains(key))
                return;
            Object v = disk.get(key);
            if (v instanceof Number n) {
                if (n.doubleValue() < 0)
                    problem(key, "is " + show(v) + " but a time must not be negative");
                return;
            }
            if (v == null || !DURATION.matcher(v.toString().trim().toLowerCase(Locale.ROOT)).matches())
                problem(key, "is " + show(v) + " which is not a time like 30s, 10m, 2h or 1h30m");
        }
    }

    private static final Pattern DURATION = Pattern.compile("(\\d+(\\.\\d+)?\\s*[dhms]?\\s*)+");
    private static final Pattern NON_NEGATIVE = Pattern.compile(
        ".*(cooldown|delay|duration|seconds|minutes|hours|ticks|radius|timeout|interval|limit|max|min|amount|size|count|distance|range|cost|price|length|width|per-|-per|every).*");

    private Guard() {
    }

    /** Result of a check: the problems and a copy of the file with every problem key put back to its shipped value. */
    public static final class Checked {
        public final List<Problem> problems;
        public final YamlConfiguration sanitized;

        Checked(List<Problem> problems, YamlConfiguration sanitized) {
            this.problems = problems;
            this.sanitized = sanitized;
        }

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    public interface Extra {
        void check(Rules rules);
    }

    public static Checked check(String file, YamlConfiguration disk, YamlConfiguration defaults, Extra extra) {
        List<Problem> problems = new ArrayList<>();
        Rules rules = new Rules(file, disk, problems);
        generic(disk, defaults, rules);
        if (extra != null)
            extra.check(rules);
        YamlConfiguration copy = new YamlConfiguration();
        for (String key : disk.getKeys(true))
            if (!disk.isConfigurationSection(key))
                copy.set(key, disk.get(key));
        for (Problem p : problems)
            copy.set(p.key, defaults.get(p.key));
        return new Checked(problems, copy);
    }

    private static void generic(YamlConfiguration disk, YamlConfiguration defaults, Rules rules) {
        for (String key : defaults.getKeys(true)) {
            Object def = defaults.get(key);
            if (!disk.contains(key))
                continue;
            Object value = disk.get(key);
            String last = key.substring(key.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (def instanceof ConfigurationSection) {
                if (value != null && !(value instanceof ConfigurationSection))
                    rules.problem(key, "is " + show(value) + " but must be a section with settings under it");
            } else if (def instanceof Number) {
                if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) {
                    rules.problem(key, "is " + show(value) + " but must be a number");
                } else if (n.doubleValue() < 0 && ((Number) def).doubleValue() >= 0 && NON_NEGATIVE.matcher(last).matches()) {
                    rules.problem(key, "is " + show(value) + " but must not be negative");
                }
            } else if (def instanceof Boolean) {
                if (!(value instanceof Boolean))
                    rules.problem(key, "is " + show(value) + " but must be true or false");
            } else if (def instanceof String) {
                if (value instanceof ConfigurationSection || value instanceof List<?>)
                    rules.problem(key, "must be a single text, not a list or section");
                if (last.equals("material") || last.endsWith("-material")) {
                    if (value != null && !value.toString().isBlank() && !isMaterial(value.toString()))
                        rules.problem(key, "is " + show(value) + " which is not an item or block");
                }
            } else if (def instanceof List<?>) {
                if (value != null && !(value instanceof List<?>))
                    rules.problem(key, "is " + show(value) + " but must be a list");
            }
        }
    }

    static boolean isMaterial(String name) {
        try {
            return Material.matchMaterial(name.trim()) != null;
        } catch (Throwable t) {
            return true; // the material table is not available (a unit test): do not report what cannot be checked
        }
    }

    private static String show(Object v) {
        if (v == null)
            return "empty";
        if (v instanceof ConfigurationSection)
            return "a section";
        if (v instanceof List<?>)
            return "a list";
        String s = v.toString();
        return "'" + (s.length() > 40 ? s.substring(0, 40) + "..." : s) + "'";
    }

    private static String trim(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** Names of the keys with a problem, for a short log line. */
    public static Set<String> keys(List<Problem> problems) {
        Set<String> out = new LinkedHashSet<>();
        for (Problem p : problems)
            out.add(p.key);
        return out;
    }
}
