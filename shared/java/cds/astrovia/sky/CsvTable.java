// Astrovia shared code: RFC 4180 CSV into rows keyed by the header (quotes, doubled quotes, line breaks inside quotes). GNU GPL v3.
package cds.astrovia.sky;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CsvTable {
   private CsvTable() { }

   public static List<Map<String, String>> parse(String text) {
      List<List<String>> records = new ArrayList<List<String>>();
      List<String> row = new ArrayList<String>();
      StringBuilder sb = new StringBuilder();
      boolean q = false;
      for (int i = 0; i < text.length(); i++) {
         char c = text.charAt(i);
         if (q) {
            if (c == '"') { if (i + 1 < text.length() && text.charAt(i + 1) == '"') { sb.append('"'); i++; } else q = false; }
            else sb.append(c);
         } else if (c == '"') q = true;
         else if (c == ',') { row.add(sb.toString()); sb.setLength(0); }
         else if (c == '\n') { row.add(sb.toString()); sb.setLength(0); records.add(row); row = new ArrayList<String>(); }
         else if (c != '\r') sb.append(c);
      }
      if (sb.length() > 0 || !row.isEmpty()) { row.add(sb.toString()); records.add(row); }
      List<Map<String, String>> out = new ArrayList<Map<String, String>>();
      if (records.size() < 2) return out;
      List<String> head = records.get(0);
      for (int r = 1; r < records.size(); r++) {
         List<String> rec = records.get(r);
         if (rec.size() <= 1 && (rec.isEmpty() || rec.get(0).isEmpty())) continue;
         Map<String, String> m = new LinkedHashMap<String, String>();
         for (int k = 0; k < head.size(); k++) m.put(head.get(k), k < rec.size() ? rec.get(k) : "");
         out.add(m);
      }
      return out;
   }
}
