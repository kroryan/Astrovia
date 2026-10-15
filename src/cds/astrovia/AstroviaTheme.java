// Copyright (C) 2023 Universite de Strasbourg/CNRS (CDS) - original Aladin Sky Atlas
// Astrovia is an independent community project (kroryan), distributed under GNU GPL v3.
package cds.astrovia;

import java.awt.Color;
import java.awt.Image;
import java.awt.Toolkit;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.UIManager;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;

/** Astrovia dark look and feel (FlatLaf Dark + Astrovia palette). */
public final class AstroviaTheme {

   public static final String NAME = "Astrovia";
   public static final String SOURCE_URL = "https://github.com/kroryan/Astrovia";

   public static final Color SKY_BLACK  = new Color(0x05070D);
   public static final Color SKY_NIGHT  = new Color(0x0B1020);
   public static final Color PANEL      = new Color(0x0E1426);
   public static final Color STROKE     = new Color(0x243049);
   public static final Color BLUE       = new Color(0x3F6FB0);
   public static final Color GOLD       = new Color(0xE8C547);
   public static final Color TEXT       = new Color(0xE8EEF7);
   public static final Color TEXT_DIM   = new Color(0x9FB0CC);
   public static final Color MARKER_RED = new Color(0xEF4444);
   public static final Color MARKER_GREEN = new Color(0x22C55E);

   private static boolean installed = false;

   private AstroviaTheme() { }

   /** Installs the look and feel (idempotent). Returns true on success. */
   public static synchronized boolean install() {
      if( installed ) return true;
      try {
         Map<String,String> d = new HashMap<String,String>();
         d.put("@background", "#0B1020");
         d.put("@foreground", "#E8EEF7");
         d.put("@accentColor", "#3F6FB0");
         d.put("@selectionBackground", "#3F6FB0");
         d.put("@selectionForeground", "#FFFFFF");
         d.put("@componentBackground", "#0E1426");
         d.put("@buttonBackground", "#16203A");
         d.put("@menuBackground", "#0E1426");
         d.put("@disabledForeground", "#5C6B88");
         d.put("Component.borderColor", "#243049");
         d.put("Component.focusColor", "#3F6FB0");
         d.put("Component.arc", "10");
         d.put("Button.arc", "10");
         d.put("TextComponent.arc", "8");
         d.put("ProgressBar.arc", "8");
         d.put("ScrollBar.thumbArc", "999");
         d.put("ScrollBar.width", "12");
         d.put("TabbedPane.selectedBackground", "#16203A");
         d.put("TabbedPane.underlineColor", "#E8C547");
         d.put("TitlePane.background", "#0E1426");
         FlatLaf.setGlobalExtraDefaults(d);
         System.setProperty("flatlaf.menuBarEmbedded", "false");
         FlatDarkLaf.setup();
         UIManager.put("Button.arc", 10);
         UIManager.put("Component.arc", 10);
         UIManager.put("TextComponent.arc", 8);
         installed = true;
      } catch( Throwable e ) {
         System.err.println("Astrovia: dark theme unavailable (" + e + ")");
      }
      return installed;
   }

   /** Window icons at several sizes (taskbar, title bar, alt-tab). */
   public static List<Image> windowIcons() {
      List<Image> l = new ArrayList<Image>();
      for( String s : new String[]{ "astrovia-icon.png" } ) {
         URL u = AstroviaTheme.class.getResource("/" + s);
         if( u != null ) l.add(Toolkit.getDefaultToolkit().getImage(u));
      }
      return l;
   }
}
