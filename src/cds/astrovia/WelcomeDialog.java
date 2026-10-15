// Copyright (C) 2023 Universite de Strasbourg/CNRS (CDS) - original Aladin Sky Atlas
// Astrovia is an independent community project (kroryan), distributed under GNU GPL v3.
package cds.astrovia;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.io.File;
import java.net.URI;
import java.net.URL;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/** Welcome / disclaimer / about window of Astrovia. Text is intentionally English only. */
public final class WelcomeDialog {

   private static final String[][] SECTIONS = {
      { "Where it comes from",
        "Astrovia is an independent project based on the Aladin Sky Atlas, developed by the Centre de Donn\u00e9es astronomiques de Strasbourg (CDS)." },
      { "Not an official app",
        "Astrovia is NOT affiliated with, endorsed by or sponsored by the CDS or the Aladin project. 'Aladin', 'CDS', 'Simbad' and 'VizieR' belong to their respective owners and are only mentioned to credit the original work and data." },
      { "Made with the help of AI",
        "This port / adaptation was created with the help of artificial intelligence tools. It may contain mistakes: please report any problem you find." },
      { "Free and open source",
        "Free, no ads and no tracking. Licensed under the GNU General Public License v3 (GPL-3.0). The source code is publicly available." },
      { "Data and privacy",
        "Astrovia connects to public astronomical services (HiPS servers, Simbad and VizieR at CDS); coordinates and names queried are sent to those servers." },
   };

   private static final String CREDITS =
      "Original software: Aladin Sky Atlas, (c) Universit\u00e9 de Strasbourg/CNRS - developed by the CDS "
    + "(authors: Pierre Fernique, Thomas Boch, Ana\u00efs Oberto, Fran\u00e7ois Bonnarel, Chaitra and contributors), "
    + "distributed under the GNU GPL v3. Astrovia is a community adaptation maintained by kroryan. "
    + "This program comes with ABSOLUTELY NO WARRANTY.";

   private WelcomeDialog() { }

   // ---- first-run persistence -------------------------------------------------

   private static File acceptedFile() {
      return new File(System.getProperty("user.home"), ".astrovia" + File.separator + "welcome.accepted");
   }

   public static boolean isAccepted() { return acceptedFile().isFile(); }

   private static void persistAccepted() {
      try {
         File f = acceptedFile();
         f.getParentFile().mkdirs();
         java.io.FileWriter w = new java.io.FileWriter(f);
         try { w.write("accepted " + System.currentTimeMillis() + "\n"); } finally { w.close(); }
      } catch( Exception e ) {
         System.err.println("Astrovia: cannot save welcome preference: " + e.getMessage());
      }
   }

   /** Shows the modal welcome window the first time Astrovia runs on this computer. */
   public static void showIfFirstRun() {
      if( isAccepted() || GraphicsEnvironment.isHeadless() ) return;
      try {
         if( SwingUtilities.isEventDispatchThread() ) show(null, true);
         else SwingUtilities.invokeAndWait(new Runnable() { public void run() { show(null, true); } });
      } catch( Exception e ) { e.printStackTrace(); }
   }

   // ---- UI -------------------------------------------------------------------

   private static void openSource() {
      try {
         if( Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) ) {
            Desktop.getDesktop().browse(new URI(AstroviaTheme.SOURCE_URL));
            return;
         }
      } catch( Exception e ) { /* fall through */ }
      try {
         String os = System.getProperty("os.name", "").toLowerCase();
         String[] cmd = os.contains("win") ? new String[]{"rundll32", "url.dll,FileProtocolHandler", AstroviaTheme.SOURCE_URL}
                                           : new String[]{"xdg-open", AstroviaTheme.SOURCE_URL};
         Runtime.getRuntime().exec(cmd);
      } catch( Exception e ) { System.err.println("Astrovia: cannot open browser: " + e.getMessage()); }
   }

   private static JTextArea text(String s, Color fg, Font font) {
      JTextArea t = new JTextArea(s);
      t.setLineWrap(true);
      t.setWrapStyleWord(true);
      t.setEditable(false);
      t.setFocusable(false);
      t.setOpaque(false);
      t.setForeground(fg);
      t.setFont(font);
      t.setBorder(null);
      t.setAlignmentX(Component.LEFT_ALIGNMENT);
      return t;
   }

   /**
    * Displays the window. In first-run mode the user must accept to continue
    * (the choice is stored in ~/.astrovia); otherwise it is the About window.
    */
   public static void show(Component parent, final boolean firstRun) {
      AstroviaTheme.install();
      final JDialog d = new JDialog((java.awt.Frame)null, firstRun ? "Welcome to Astrovia" : "About Astrovia", true);
      d.setIconImages(AstroviaTheme.windowIcons());

      JPanel root = new JPanel(new BorderLayout(0, 12));
      root.setBackground(AstroviaTheme.SKY_NIGHT);
      root.setBorder(new EmptyBorder(20, 24, 16, 24));

      // header: logo + title
      JPanel head = new JPanel(new BorderLayout(16, 0));
      head.setOpaque(false);
      URL u = WelcomeDialog.class.getResource("/astrovia-icon.png");
      if( u != null ) {
         Image im = new ImageIcon(u).getImage().getScaledInstance(96, 96, Image.SCALE_SMOOTH);
         head.add(new JLabel(new ImageIcon(im)), BorderLayout.WEST);
      }
      JPanel titles = new JPanel();
      titles.setOpaque(false);
      titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
      JLabel t1 = new JLabel(firstRun ? "Welcome to Astrovia" : "About Astrovia");
      t1.setFont(t1.getFont().deriveFont(Font.BOLD, 26f));
      t1.setForeground(AstroviaTheme.TEXT);
      JLabel t2 = new JLabel("Astrovia - Open-source sky atlas");
      t2.setFont(t2.getFont().deriveFont(Font.PLAIN, 14f));
      t2.setForeground(AstroviaTheme.GOLD);
      titles.add(Box.createVerticalGlue());
      titles.add(t1);
      titles.add(Box.createVerticalStrut(4));
      titles.add(t2);
      titles.add(Box.createVerticalGlue());
      head.add(titles, BorderLayout.CENTER);
      root.add(head, BorderLayout.NORTH);

      // body
      JPanel body = new JPanel();
      body.setOpaque(false);
      body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
      Font base = new JLabel().getFont();
      for( String[] s : SECTIONS ) {
         JLabel h = new JLabel(s[0]);
         h.setFont(base.deriveFont(Font.BOLD, 13f));
         h.setForeground(AstroviaTheme.GOLD);
         h.setAlignmentX(Component.LEFT_ALIGNMENT);
         body.add(h);
         body.add(Box.createVerticalStrut(2));
         body.add(text(s[1], AstroviaTheme.TEXT, base.deriveFont(Font.PLAIN, 13f)));
         body.add(Box.createVerticalStrut(10));
      }
      JLabel ch = new JLabel("Credits and licence");
      ch.setFont(base.deriveFont(Font.BOLD, 12f));
      ch.setForeground(AstroviaTheme.TEXT_DIM);
      ch.setAlignmentX(Component.LEFT_ALIGNMENT);
      body.add(ch);
      body.add(Box.createVerticalStrut(2));
      body.add(text(CREDITS, AstroviaTheme.TEXT_DIM, base.deriveFont(Font.PLAIN, 11f)));

      JScrollPane sp = new JScrollPane(body);
      sp.setBorder(null);
      sp.setOpaque(false);
      sp.getViewport().setOpaque(false);
      sp.getVerticalScrollBar().setUnitIncrement(16);
      sp.setPreferredSize(new Dimension(600, 430));
      root.add(sp, BorderLayout.CENTER);

      // buttons
      JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
      btns.setOpaque(false);
      JButton src = new JButton("Source code");
      src.addActionListener(new java.awt.event.ActionListener() {
         public void actionPerformed(java.awt.event.ActionEvent e) { openSource(); }
      });
      JButton ok = new JButton(firstRun ? "I understand, continue" : "Close");
      ok.setBackground(AstroviaTheme.BLUE);
      ok.setForeground(Color.WHITE);
      ok.addActionListener(new java.awt.event.ActionListener() {
         public void actionPerformed(java.awt.event.ActionEvent e) {
            if( firstRun ) persistAccepted();
            d.dispose();
         }
      });
      btns.add(src);
      btns.add(ok);
      root.add(btns, BorderLayout.SOUTH);

      d.setContentPane(root);
      d.getRootPane().setDefaultButton(ok);
      if( firstRun ) {
         d.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
         d.addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent e) { System.exit(0); }
         });
      }
      d.pack();
      ok.requestFocusInWindow();
      d.setLocationRelativeTo(parent);
      d.setVisible(true);
   }

   /** For manual testing: java -cp Astrovia.jar cds.astrovia.WelcomeDialog */
   public static void main(String[] a) { show(null, a.length == 0); System.exit(0); }
}
