package dev.storetrack.safe;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brings a config.yml or messages.yml up to date with the file the plugin ships, without touching anything the owner
 * wrote. It works on the text of the file, so comments, spacing and the order of keys stay exactly as they were:
 * <ul>
 * <li>a key that the shipped file has and the file on disk lacks is added, with the shipped comments;</li>
 * <li>a key is only ever removed or renamed when this migrator was told to, for a named version;</li>
 * <li>the {@code versionKey} line is brought to the current number;</li>
 * <li>the old file is kept as {@code name.<time>.bak} before anything is written;</li>
 * <li>a file with a newer version than this plugin knows is left alone.</li>
 * </ul>
 */
public final class ConfigMigrator {

    public enum Outcome { UP_TO_DATE, MIGRATED, TOO_NEW, FAILED }

    public static final class Result {
        public final Outcome outcome;
        public final int fromVersion;
        public final List<String> changes;

        Result(Outcome outcome, int fromVersion, List<String> changes) {
            this.outcome = outcome;
            this.fromVersion = fromVersion;
            this.changes = changes;
        }
    }

    private static final class Step {
        final int since;
        final String from;
        final String to; // null = remove

        Step(int since, String from, String to) {
            this.since = since;
            this.from = from;
            this.to = to;
        }
    }

    private static final class Node {
        String key;
        String path;
        int indent;
        int line;
        int commentStart;
        int end; // exclusive
    }

    private static final int KEEP_BACKUPS = 5;
    private static final Pattern KEY = Pattern.compile("^( *)(?:\"((?:[^\"\\\\]|\\\\.)*)\"|'((?:[^']|'')*)'|([^\\s#'\"\\-\\[{][^#]*?))[ ]*:(?: .*|)$");
    private static final Pattern VERSION_VALUE = Pattern.compile("^(\\s*[^:]+:\\s*)-?\\d+(.*)$");

    private final String versionKey;
    private final int current;
    private final List<Step> steps = new ArrayList<>();

    /** @param versionKey the integer key at the top of the file, such as config-version */
    public ConfigMigrator(String versionKey, int current) {
        this.versionKey = versionKey;
        this.current = current;
    }

    /** Removes a key (and everything under it) from files whose version is lower than {@code since}. */
    public ConfigMigrator remove(int since, String path) {
        steps.add(new Step(since, path, null));
        return this;
    }

    /** Renames or moves a key, with its value and comments, for files whose version is lower than {@code since}. */
    public ConfigMigrator rename(int since, String from, String to) {
        steps.add(new Step(since, from, to));
        return this;
    }

    /** The text without that key and everything under it. For tests and tools. */
    public static String withoutKey(String text, String path) {
        boolean endsWithEol = text.endsWith("\n");
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = split(text);
        removeKey(lines, path);
        return join(lines, eol, endsWithEol);
    }

    public Result run(Path file, String defaultsText, Logger log) {
        List<String> changes = new ArrayList<>();
        String name = file.getFileName().toString();
        try {
            String original = Files.readString(file, StandardCharsets.UTF_8);
            if (original.startsWith("﻿"))
                original = original.substring(1);
            YamlConfiguration disk = new YamlConfiguration();
            disk.loadFromString(original);
            int version = disk.getInt(versionKey, 0);
            if (version > current) {
                log.warning(name + " has " + versionKey + " " + version + ", which is newer than this plugin understands (" + current
                    + "). It was left as it is. Use the plugin version it came from, or fix the number.");
                Health.file(name, "newer than this plugin (" + versionKey + " " + version + "), left untouched");
                return new Result(Outcome.TOO_NEW, version, changes);
            }

            String eol = original.contains("\r\n") ? "\r\n" : "\n";
            boolean endsWithEol = original.endsWith("\n");
            List<String> lines = split(original);
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.loadFromString(defaultsText);
            List<String> defaultLines = split(defaultsText);

            Set<String> dropped = new HashSet<>();
            for (Step step : steps) {
                if (step.since <= version || step.since > current)
                    continue;
                if (step.to == null) {
                    if (removeKey(lines, step.from)) {
                        changes.add("removed '" + step.from + "'");
                        dropped.add(step.from);
                    }
                } else if (renameKey(lines, step.from, step.to)) {
                    changes.add("renamed '" + step.from + "' to '" + step.to + "'");
                    dropped.add(step.from);
                }
            }

            YamlConfiguration afterSteps = new YamlConfiguration();
            afterSteps.loadFromString(join(lines, eol, endsWithEol));
            addMissing(lines, defaultLines, defaults, afterSteps, changes, log);

            boolean hadVersion = disk.contains(versionKey);
            setVersion(lines);
            if (!hadVersion || version != current)
                changes.add(versionKey + " set to " + current);

            String result = join(lines, eol, endsWithEol || lines.size() > 0);
            if (result.equals(original)) {
                Health.file(name, "up to date (" + versionKey + " " + current + ")");
                return new Result(Outcome.UP_TO_DATE, version, changes);
            }

            YamlConfiguration after = new YamlConfiguration();
            after.loadFromString(result);
            for (String path : disk.getKeys(true)) {
                if (disk.isConfigurationSection(path) || isDropped(path, dropped) || path.equals(versionKey))
                    continue;
                if (!Objects.equals(disk.get(path), after.get(path))) {
                    log.severe("Updating " + name + " would have changed the value of '" + path + "', so nothing was written. Please report this.");
                    Health.file(name, "update refused (would change '" + path + "')");
                    return new Result(Outcome.FAILED, version, changes);
                }
            }

            Path backup = file.resolveSibling(name + "." + SafeIo.stamp() + ".bak");
            SafeIo.write(backup, original);
            SafeIo.writeYaml(file, result);
            prune(file, name);
            log.info(name + " was updated to " + versionKey + " " + current + " (" + changes.size() + " change(s)): " + String.join("; ", changes)
                + ". The old file is kept as " + backup.getFileName() + ".");
            Health.file(name, "updated to " + versionKey + " " + current);
            return new Result(Outcome.MIGRATED, version, changes);
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            log.warning(name + " could not be updated: " + e.getMessage());
            Health.file(name, "update failed: " + e.getMessage());
            return new Result(Outcome.FAILED, 0, changes);
        }
    }

    // ---------------------------------------------------------------- text model

    private static List<String> split(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty())
            lines.remove(lines.size() - 1);
        return lines;
    }

    private static String join(List<String> lines, String eol, boolean endsWithEol) {
        return String.join(eol, lines) + (endsWithEol && !lines.isEmpty() ? eol : "");
    }

    private static boolean isDropped(String path, Set<String> dropped) {
        for (String d : dropped)
            if (path.equals(d) || path.startsWith(d + "."))
                return true;
        return false;
    }

    private static List<Node> parse(List<String> lines) {
        List<Node> nodes = new ArrayList<>();
        Deque<Node> stack = new ArrayDeque<>();
        int listIndent = -1;
        int rawIndent = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String t = line.stripLeading();
            if (t.isEmpty())
                continue;
            int indent = line.length() - t.length();
            if (rawIndent >= 0) {
                if (indent > rawIndent)
                    continue;
                rawIndent = -1;
            }
            if (t.startsWith("#"))
                continue;
            if (listIndent >= 0) {
                if (indent > listIndent || (indent == listIndent && (t.startsWith("- ") || t.equals("-"))))
                    continue;
                listIndent = -1;
            }
            if (t.startsWith("- ") || t.equals("-")) {
                listIndent = indent;
                continue;
            }
            Matcher m = KEY.matcher(line);
            if (!m.matches())
                continue;
            String key = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3).replace("''", "'") : m.group(4).strip();
            Node node = new Node();
            node.key = key;
            node.indent = indent;
            node.line = i;
            while (!stack.isEmpty() && stack.peek().indent >= indent)
                stack.pop();
            node.path = stack.isEmpty() ? key : stack.peek().path + "." + key;
            stack.push(node);
            nodes.add(node);
            int colon = colonIndex(line);
            String rest = colon >= 0 ? line.substring(colon + 1).strip() : "";
            if (rest.startsWith("|") || rest.startsWith(">"))
                rawIndent = indent;
        }
        for (int k = 0; k < nodes.size(); k++) {
            Node n = nodes.get(k);
            int start = n.line;
            while (start - 1 >= 0 && lines.get(start - 1).stripLeading().startsWith("#")) {
                start--;
            }
            n.commentStart = start;
        }
        for (int k = 0; k < nodes.size(); k++) {
            Node n = nodes.get(k);
            int end = lines.size();
            for (int j = k + 1; j < nodes.size(); j++) {
                if (nodes.get(j).indent <= n.indent) {
                    end = Math.max(n.line + 1, nodes.get(j).commentStart);
                    break;
                }
            }
            while (end - 1 > n.line && lines.get(end - 1).isBlank())
                end--;
            n.end = end;
        }
        return nodes;
    }

    /** Where the colon of a key line is, or -1 if the line is not a key line. */
    private static int colonIndex(String line) {
        Matcher m = KEY.matcher(line);
        if (!m.matches())
            return -1;
        int keyEnd = m.group(2) != null ? m.end(2) + 1 : m.group(3) != null ? m.end(3) + 1 : m.end(4);
        return line.indexOf(':', keyEnd);
    }

    private static Node find(List<Node> nodes, String path) {
        for (Node n : nodes)
            if (n.path.equals(path))
                return n;
        return null;
    }

    private static String parentOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(0, dot);
    }

    private static String lastOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    // ---------------------------------------------------------------- operations

    private static boolean removeKey(List<String> lines, String path) {
        Node node = find(parse(lines), path);
        if (node == null)
            return false;
        for (int i = node.end - 1; i >= node.commentStart; i--)
            lines.remove(i);
        return true;
    }

    private static boolean renameKey(List<String> lines, String from, String to) {
        List<Node> nodes = parse(lines);
        Node node = find(nodes, from);
        if (node == null || find(nodes, to) != null)
            return false;
        String newKey = lastOf(to);
        if (parentOf(from).equals(parentOf(to))) {
            String line = lines.get(node.line);
            int colon = colonIndex(line);
            lines.set(node.line, " ".repeat(node.indent) + newKey + line.substring(colon));
            return true;
        }
        String parent = parentOf(to);
        Node target = parent.isEmpty() ? null : find(nodes, parent);
        if (!parent.isEmpty() && target == null)
            return false;
        List<String> block = new ArrayList<>(lines.subList(node.commentStart, node.end));
        int keyOffset = node.line - node.commentStart;
        String keyLine = block.get(keyOffset);
        int colon = colonIndex(keyLine);
        block.set(keyOffset, " ".repeat(node.indent) + newKey + keyLine.substring(colon));
        for (int i = node.end - 1; i >= node.commentStart; i--)
            lines.remove(i);
        nodes = parse(lines);
        insertBlock(lines, nodes, parent, block, node.indent);
        return true;
    }

    private void addMissing(List<String> lines, List<String> defaultLines, YamlConfiguration defaults,
                                   YamlConfiguration disk, List<String> changes, Logger log) {
        Set<String> missing = new LinkedHashSet<>();
        for (String path : defaults.getKeys(true))
            if (!disk.contains(path) && !path.equals(versionKey))
                missing.add(path);
        for (String path : missing) {
            String parent = parentOf(path);
            if (!parent.isEmpty() && missing.contains(parent))
                continue; // inserted together with its parent
            if (!parent.isEmpty() && !disk.isConfigurationSection(parent)) {
                boolean empty = disk.contains(parent) && disk.get(parent) == null;
                if (!empty)
                    continue; // the parent is something else on disk: a validator reports that
            }
            List<Node> defNodes = parse(defaultLines);
            Node def = find(defNodes, path);
            if (def == null)
                continue;
            List<String> block = new ArrayList<>(defaultLines.subList(def.commentStart, def.end));
            if (insertBlock(lines, parse(lines), parent, block, def.indent))
                changes.add("added '" + path + "'");
            else
                log.warning("Could not add the missing key '" + path + "'; the plugin uses its default for it.");
        }
    }

    /** Puts a block (comments + key + children, written at {@code blockIndent}) at the end of the parent section. */
    private static boolean insertBlock(List<String> lines, List<Node> nodes, String parentPath, List<String> block, int blockIndent) {
        int at;
        int indent;
        if (parentPath.isEmpty()) {
            at = lines.size();
            while (at > 0 && lines.get(at - 1).isBlank())
                at--;
            indent = 0;
            if (at > 0) {
                lines.add(at, "");
                at++;
            }
        } else {
            Node parent = find(nodes, parentPath);
            if (parent == null)
                return false;
            String line = lines.get(parent.line);
            int colon = colonIndex(line);
            String after = line.substring(colon + 1).strip();
            if (after.equals("{}") || after.equals("~") || after.equalsIgnoreCase("null"))
                lines.set(parent.line, line.substring(0, colon + 1));
            else if (!after.isEmpty() && !after.startsWith("#"))
                return false; // a value, not a section
            Node first = null;
            for (Node n : nodes)
                if (n.indent > parent.indent && n.line > parent.line && n.line < parent.end && parentOf(n.path).equals(parentPath)) {
                    first = n;
                    break;
                }
            indent = first != null ? first.indent : parent.indent + 2;
            at = parent.end;
        }
        int delta = indent - blockIndent;
        List<String> moved = new ArrayList<>();
        for (String l : block) {
            if (l.isBlank()) {
                moved.add("");
            } else if (delta >= 0) {
                moved.add(" ".repeat(delta) + l);
            } else {
                int strip = 0;
                while (strip < -delta && strip < l.length() && l.charAt(strip) == ' ')
                    strip++;
                moved.add(l.substring(strip));
            }
        }
        lines.addAll(at, moved);
        return true;
    }

    private void setVersion(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.isEmpty() || l.charAt(0) == ' ' || l.charAt(0) == '#')
                continue;
            Matcher m = KEY.matcher(l);
            if (!m.matches())
                continue;
            String key = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4).strip();
            if (!key.equals(versionKey))
                continue;
            Matcher v = VERSION_VALUE.matcher(l);
            lines.set(i, v.matches() ? v.group(1) + current + v.group(2) : versionKey + ": " + current);
            return;
        }
        lines.add(0, versionKey + ": " + current);
    }

    private static void prune(Path file, String name) {
        TreeSet<Path> backups = new TreeSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(file.getParent(), name + ".*.bak")) {
            for (Path p : stream)
                backups.add(p);
        } catch (IOException ignored) {
            return;
        }
        while (backups.size() > KEEP_BACKUPS) {
            try {
                Files.deleteIfExists(backups.pollFirst());
            } catch (IOException ignored) {
                return;
            }
        }
    }
}
