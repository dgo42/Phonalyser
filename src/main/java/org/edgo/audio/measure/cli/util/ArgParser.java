package org.edgo.audio.measure.cli.util;

import java.util.ArrayList;
import java.util.List;

public final class ArgParser {

    private ArgParser() {}

    public static boolean hasArg(String[] args, String flag) {
        for (String a : args) {
            if (a.equalsIgnoreCase(flag)) {
                return true;
            }
        }
        return false;
    }

    public static String getArgValue(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equalsIgnoreCase(flag)) {
                return args[i + 1];
            }
        }
        return null;
    }

    /** Collects every value following {@code flag} in {@code args}, in order. */
    public static List<String> getArgValues(String[] args, String flag) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equalsIgnoreCase(flag)) out.add(args[i + 1]);
        }
        return out;
    }
}
