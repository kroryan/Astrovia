// Astrovia shared code: VOTable answers of TAP services (TABLEDATA and BINARY base64 streams), first table only. GNU GPL v3.
package cds.astrovia.sky;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Some archives answer in TABLEDATA (MAST), others only in BINARY (HEASARC); a service that failed answers with
 * QUERY_STATUS=ERROR, which is raised as an IOException with the archive's message.
 */
public final class VotableReader {
   private VotableReader() { }

   private static final class Field { final String name, datatype, arraysize; Field(String n, String d, String a) { name = n; datatype = d; arraysize = a; } }

   private static final class Done extends SAXException { Done() { super("done"); } }

   public static List<Map<String, String>> parse(String xml) throws IOException {
      final List<Field> fields = new ArrayList<Field>();
      final List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
      final IOException[] error = new IOException[1];
      DefaultHandler h = new DefaultHandler() {
         StringBuilder text = new StringBuilder();
         List<String> cur = new ArrayList<String>();
         boolean inData, inInfoError;
         public void startElement(String uri, String local, String q, Attributes a) {
            String n = name(local, q);
            text.setLength(0);
            if (n.equals("INFO") && "QUERY_STATUS".equals(a.getValue("name")) && "ERROR".equals(a.getValue("value"))) inInfoError = true;
            else if (n.equals("FIELD")) fields.add(new Field(a.getValue("name") == null ? "col" + (fields.size() + 1) : a.getValue("name"), a.getValue("datatype") == null ? "char" : a.getValue("datatype"), a.getValue("arraysize")));
            else if (n.equals("TABLEDATA")) inData = true;
            else if (n.equals("TR") && inData) cur = new ArrayList<String>();
         }
         public void characters(char[] ch, int s, int l) { text.append(ch, s, l); }
         public void endElement(String uri, String local, String q) throws SAXException {
            String n = name(local, q);
            if (n.equals("INFO") && inInfoError) { String m = text.toString().trim(); error[0] = new IOException(m.isEmpty() ? "The archive rejected the query" : m); throw new Done(); }
            else if (n.equals("TD") && inData) cur.add(text.toString().trim());
            else if (n.equals("TR") && inData) { Map<String, String> m = new LinkedHashMap<String, String>(); for (int i = 0; i < fields.size(); i++) m.put(fields.get(i).name, i < cur.size() ? cur.get(i) : ""); rows.add(m); }
            else if (n.equals("TABLEDATA")) throw new Done();
            else if (n.equals("STREAM")) { try { rows.addAll(decodeBinary(base64(text.toString()), fields)); } catch (IOException e) { error[0] = e; } throw new Done(); }
            text.setLength(0);
         }
      };
      try {
         SAXParserFactory f = SAXParserFactory.newInstance();
         f.setNamespaceAware(false);
         try { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); } catch (Exception ignored) { /* not supported by every parser */ }
         SAXParser p = f.newSAXParser();
         p.parse(new InputSource(new StringReader(xml)), h);
      } catch (Done done) {
         // first table read
      } catch (SAXException e) {
         throw new IOException("Not a valid VOTable: " + e.getMessage());
      } catch (javax.xml.parsers.ParserConfigurationException e) {
         throw new IOException(e.getMessage());
      }
      if (error[0] != null) throw error[0];
      return rows;
   }

   private static String name(String local, String q) {
      String n = local != null && !local.isEmpty() ? local : q;
      int c = n.indexOf(':');
      return c >= 0 ? n.substring(c + 1) : n;
   }

   /** Base64 decoder of our own: java.util.Base64 does not exist on Android 7.0 and 7.1. */
   static byte[] base64(String s) {
      int[] map = new int[128];
      java.util.Arrays.fill(map, -1);
      String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
      for (int i = 0; i < abc.length(); i++) map[abc.charAt(i)] = i;
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length() * 3 / 4);
      int buf = 0, bits = 0;
      for (int i = 0; i < s.length(); i++) {
         char c = s.charAt(i);
         if (c >= 128 || map[c] < 0) continue;          // padding, spaces and line breaks
         buf = (buf << 6) | map[c]; bits += 6;
         if (bits >= 8) { bits -= 8; out.write((buf >> bits) & 0xFF); }
      }
      return out.toByteArray();
   }

   private static List<Map<String, String>> decodeBinary(byte[] bytes, List<Field> fields) throws IOException {
      ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
      List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
      while (bb.hasRemaining()) {
         Map<String, String> row = new LinkedHashMap<String, String>();
         try { for (Field f : fields) row.put(f.name, value(bb, f)); }
         catch (java.nio.BufferUnderflowException e) { break; }
         rows.add(row);
      }
      return rows;
   }

   private static String value(ByteBuffer bb, Field f) throws IOException {
      String size = f.arraysize;
      int count;
      if (size == null) count = 1;
      else if (size.endsWith("*")) count = bb.getInt();
      else { count = 1; for (String p : size.split("x")) { try { count *= Integer.parseInt(p.trim()); } catch (NumberFormatException e) { /* keep */ } } }
      if (f.datatype.equals("char")) { byte[] b = new byte[count]; bb.get(b); return new String(b, java.nio.charset.Charset.forName("ISO-8859-1")).trim(); }
      if (f.datatype.equals("unicodeChar")) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < count; i++) sb.append(bb.getChar()); return sb.toString().trim(); }
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < count; i++) {
         if (i == 4 && count > 4) { sb.append(",..."); for (int k = 4; k < count; k++) one(bb, f.datatype); break; }
         if (i > 0) sb.append(',');
         sb.append(one(bb, f.datatype));
      }
      return sb.toString();
   }

   private static String one(ByteBuffer bb, String dt) throws IOException {
      switch (dt) {
         case "double": { double d = bb.getDouble(); return Double.isNaN(d) ? "" : Double.toString(d); }
         case "float": { float v = bb.getFloat(); return Float.isNaN(v) ? "" : Float.toString(v); }
         case "int": return Integer.toString(bb.getInt());
         case "short": return Short.toString(bb.getShort());
         case "long": return Long.toString(bb.getLong());
         case "unsignedByte": return Integer.toString(bb.get() & 0xFF);
         case "boolean": { byte b = bb.get(); return (b == 'T' || b == 't' || b == 1) ? "true" : "false"; }
         default: throw new IOException("Unsupported VOTable datatype " + dt);
      }
   }
}
