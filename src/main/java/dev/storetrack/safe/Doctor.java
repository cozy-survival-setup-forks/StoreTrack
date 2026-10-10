package dev.storetrack.safe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The text of the doctor command. Status and health only: never balances, names or other player data. */
public final class Doctor {

    private Doctor() {
    }

    /**
     * @param extra lines from the plugin itself (schema or config versions, queue length, how many entries need review)
     */
    public static List<String> report(String plugin, String version, List<String> extra) {
        List<String> out = new ArrayList<>();
        out.add(plugin + " " + version + " - doctor");
        out.add("Storage: " + Health.storageLine());
        for (Map.Entry<String, String> e : Health.files().entrySet())
            out.add("File " + e.getKey() + ": " + e.getValue());
        out.addAll(extra);
        out.add("Last backup: " + Health.backupLine());
        List<String> failures = Health.failures();
        if (failures.isEmpty()) {
            out.add("Recent save failures: none");
        } else {
            out.add("Recent save failures (" + failures.size() + "):");
            for (String f : failures)
                out.add("  " + f);
        }
        return out;
    }
}
