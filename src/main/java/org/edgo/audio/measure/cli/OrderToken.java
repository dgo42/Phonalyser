package org.edgo.audio.measure.cli;

import lombok.Value;

import java.util.ArrayList;
import java.util.List;

/** Order descriptor: literal integer power or {@code h+offset}/{@code h-offset}. */
@Value
public class OrderToken {
    boolean hRelative;   // true → h + offset
    int     value;       // literal power (hRelative=false) OR offset added to h
    String  label;       // original textual form

    public int resolve(int h) { return hRelative ? h + value : value; }

    /** Parses a comma-separated list of order tokens (integer literal or "h±k"). */
    public static List<OrderToken> parseList(String spec) {
        List<OrderToken> out = new ArrayList<>();
        for (String tok : spec.split(",")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            if (t.equalsIgnoreCase("h")) {
                out.add(new OrderToken(true, 0, "h"));
            } else if (t.toLowerCase().startsWith("h+") || t.toLowerCase().startsWith("h-")) {
                int off = Integer.parseInt(t.substring(1));   // "+1", "-2"
                out.add(new OrderToken(true, off, t));
            } else {
                out.add(new OrderToken(false, Integer.parseInt(t), t));
            }
        }
        return out;
    }

    public static String renderList(List<OrderToken> orders) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < orders.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(orders.get(i).getLabel());
        }
        return sb.toString();
    }
}
