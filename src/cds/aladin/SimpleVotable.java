// Astrovia: tiny VOTable (TABLEDATA) reader for archive answers. Part of Astrovia, GNU GPL v3.
package cds.aladin;

import java.util.*;
import java.util.regex.*;

final class SimpleVotable {
   private SimpleVotable() { }

   private static String unescape(String s) {
      return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&").trim();
   }

   /** Rows of the first table as column-name -> value maps. Throws when the archive answered an error or a BINARY table. */
   static List<Map<String, String>> parse(String xml) throws java.io.IOException {
      Matcher err = Pattern.compile("QUERY_STATUS\"\\s+value=\"ERROR\"[^>]*>([^<]*)").matcher(xml);
      if (err.find()) throw new java.io.IOException(unescape(err.group(1)).isEmpty() ? "The archive rejected the query" : unescape(err.group(1)));
      if (xml.contains("<BINARY") && !xml.contains("<TABLEDATA")) throw new java.io.IOException("This archive answered in binary VOTable; use the Search tab to load it into Astrovia");
      List<String> names = new ArrayList<String>();
      Matcher f = Pattern.compile("<FIELD\\b[^>]*?\\bname=\"([^\"]*)\"").matcher(xml);
      while (f.find()) names.add(unescape(f.group(1)));
      List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
      Matcher tr = Pattern.compile("<TR>(.*?)</TR>", Pattern.DOTALL).matcher(xml);
      Pattern td = Pattern.compile("<TD[^>]*>(.*?)</TD>|<TD\\s*/>", Pattern.DOTALL);
      while (tr.find()) {
         Matcher c = td.matcher(tr.group(1));
         Map<String, String> row = new LinkedHashMap<String, String>();
         int i = 0;
         while (c.find() && i < names.size()) { row.put(names.get(i++), c.group(1) == null ? "" : unescape(c.group(1))); }
         rows.add(row);
      }
      return rows;
   }
}
