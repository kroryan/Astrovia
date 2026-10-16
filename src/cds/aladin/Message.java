// Copyright 1999-2022 - Universite de Strasbourg/CNRS
// The Aladin Desktop program is developped by the Centre de Donnees
// astronomiques de Strasbourgs (CDS).
// The Aladin Desktop program is distributed under the terms
// of the GNU General Public License version 3.
//
//This file is part of Aladin Desktop.
//
//    Aladin Desktop is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, version 3 of the License.
//
//    Aladin Desktop is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//    GNU General Public License for more details.
//
//    The GNU General Public License is available in COPYING file
//    along with Aladin Desktop.
//

package cds.aladin;


import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Panel;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.StringTokenizer;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.UIManager;

import cds.tools.Util;

/**
 * Gestion des messages
 * <p>Description: </p>
 * <p>Copyright: Copyright (c) 2003</p>
 * <p>Company: </p>
 * @author non attribué
 * @version 1.0
 */

public class Message extends JPanel {
    static final int NON=0;
    static final int OUI=1;
    static final int MESSAGE=0;
    static final int CONFIRME=1;
    static final int WARNING=2;
    static final int PASSWORD=4;
    static final int QUESTION=5;

    int type;
    int value=NON;

    static JButton ok,oui,non;
    static JTextField user;
    static JPasswordField passwd;
    static Dialog dialog;
    static Message currentMessage=null;

    public Message(String message,Panel myPanel,int type) {
        super();
        JPanel p = getPanel(message==null?"[*EMPTY*]":message,myPanel,type);
        add(p);
    }
    
    protected JPanel getPanel(String message,Panel myPanel,int type) {
        JPanel p = new JPanel();
        this.type = type;

        GridBagLayout g = new GridBagLayout();
        p.setLayout(g);
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.NONE;
        c.gridwidth = GridBagConstraints.REMAINDER;

        JLabel a;
        c.anchor = GridBagConstraints.WEST;
        
        // On replie les lignes si nécessaire
        if( message.length()>80 && message.indexOf('\n')<0 ) {
           message = Util.fold(message, 60);
        }
        
        StringTokenizer st = new StringTokenizer(message,"\n");
        int MH=0;
        int mh=10;
        int mg;
        int MG=20;
        int max;
        while( st.hasMoreTokens() ) {
           String s=st.nextToken().trim();
           int taille=12;
           if( s.length()==0 ) { mh+=12; continue; }
           if( s.charAt(0)=='.' ) { mg=MG+20; max=55;}
           else if( s.charAt(0)=='!') {
              s=s.substring(1);
              taille=14;
              mg=MG+20;
              max=30;
           }
           else { mg=MG; max=60; }
           int n=0;
           StringBuffer b = new StringBuffer();
           StringTokenizer sta = new StringTokenizer(s," ");
           while( sta.hasMoreTokens() ) {
              if( n>0 ) b.append(" ");
              String mot = sta.nextToken();
              n+=mot.length()+1;
              b.append(mot);
              if( !sta.hasMoreTokens() || n>max ) {
                 String ligne=b.toString();
                 a = new JLabel(ligne);
                 if( type==WARNING) {
                    a.setFont(a.getFont().deriveFont(Font.BOLD));
                    a.setForeground( Aladin.COLOR_RED );
                 }
                 c.insets = new Insets(mh,mg,0,20); mh=MH;
                 g.setConstraints(a,c);
                 p.add(a);
                 b = new StringBuffer();
                 n=0;
              }
           }
        }

        c.anchor = GridBagConstraints.CENTER;
        c.insets = new Insets(10,20,10,20);
        JPanel pb = new JPanel();
        if( type==CONFIRME ) {
           non = new JButton("No");
           non.addActionListener(new ActionListener()  {
              public void actionPerformed(ActionEvent e) { value=NON; dialog.dispose(); }
           });
           pb.add(non);
           oui = new JButton("Yes");
           oui.addActionListener(new ActionListener()  {
              public void actionPerformed(ActionEvent e) { value=OUI; dialog.dispose(); }
           });
           pb.add(oui);
        } else if( type==PASSWORD ) {
           user = new JTextField(15);
           passwd = new JPasswordField(15);
           passwd.setEchoChar('.');
           JPanel panelPwd = new JPanel();
           panelPwd.setLayout(new GridLayout(2,2));
           panelPwd.add(new JLabel("User name:"));
           panelPwd.add(user);
           panelPwd.add(new JLabel("Password:"));
           panelPwd.add(passwd);
           g.setConstraints(panelPwd,c);
           p.add(panelPwd);
           
           oui = new JButton("Ok");
           oui.addActionListener(new ActionListener()  {
              public void actionPerformed(ActionEvent e) { value=OUI; dialog.dispose(); }
           });
           pb.add(oui);
           non = new JButton("Cancel");
           non.addActionListener(new ActionListener()  {
              public void actionPerformed(java.awt.event.ActionEvent e) { value=NON; dialog.dispose(); }
           });
           pb.add(non);
        } else if( type==QUESTION ) {
           themeAwt(myPanel);
           g.setConstraints(myPanel,c);
           p.add(myPanel);
          oui = new JButton("Ok");
           oui.addActionListener(new ActionListener()  {
              public void actionPerformed(ActionEvent e) { value=OUI; dialog.dispose(); }
           });
           pb.add(oui);
           non = new JButton("Cancel");
           non.addActionListener(new ActionListener()  {
              public void actionPerformed(java.awt.event.ActionEvent e) { value=NON; dialog.dispose(); }
           });
           pb.add(non);
       } else {        
           ok = new JButton("OK");
           ok.addActionListener(new ActionListener()  {
               public void actionPerformed(ActionEvent e) { dialog.dispose(); }
           });
           pb.add(ok);
        }
        
        if( oui!=null ) oui.requestFocusInWindow();
        
        g.setConstraints(pb,c);
        p.add(pb);
        return p;
    }

    public static int showPassword(String message,StringBuffer u,StringBuffer p) {
       int rep=showFrame(Aladin.aladin,message,null,PASSWORD);
       if( rep==NON) return NON;
       u.append(user.getText());
       p.append(new String(passwd.getPassword()));
       return OUI;
    }


    public static int showWarning(Component c, String message) {
        return showFrame(c,message,null,WARNING);
    }

    public static int showMessage(Component c, String message) {
        return showFrame(c,message,null,MESSAGE);
    }

    public static int showConfirme(Component c, String message) {
       return showFrame(c,message,null,CONFIRME);
   }

    public static int showQuestion(Component c, String message,Panel myPanel) {
       return showFrame(c,message,myPanel,QUESTION);
   }

    public static int showFrame(Component c,String message,Panel myPanel,int type) {
        if( currentMessage!=null &&
                (type==CONFIRME || currentMessage.type==CONFIRME
                      || type==PASSWORD || currentMessage.type==PASSWORD
                      || type==QUESTION || currentMessage.type==QUESTION)
              ) {
           dialog.dispose();
           currentMessage=null;
        }
        if( currentMessage==null ) {
           currentMessage = new Message(message,myPanel,type);
           dialog = currentMessage.createDialog(c, type==WARNING?"Warning":type==MESSAGE?"Information":
                                                   type==PASSWORD?"Authentication":
                                                   type==QUESTION?"Question":"Confirmation");
        } else {
           currentMessage.remove(0);
           currentMessage.add( currentMessage.getPanel(message,myPanel,type));
           dialog.pack();
        }
        
        dialog.setVisible(true);
        
        // Juste pour passer la main à un autre thread
        try { Thread.currentThread().sleep(100); } catch( Exception e ) {}

        return currentMessage.value;
    }
    
    public static void hideFrame() { if( dialog!=null ) dialog.dispose(); }


    /** AWT containers/components do not follow the Swing look and feel: paint them with the current theme. */
    static void themeAwt(Component c) {
        if( c==null ) return;
        java.awt.Color bg = UIManager.getColor("Panel.background");
        java.awt.Color fg = UIManager.getColor("Label.foreground");
        if( bg!=null && !(c instanceof javax.swing.JComponent) ) c.setBackground(bg);
        if( fg!=null && !(c instanceof javax.swing.JComponent) ) c.setForeground(fg);
        if( c instanceof java.awt.Container ) {
            for( Component k : ((java.awt.Container)c).getComponents() ) themeAwt(k);
        }
    }

    public Dialog createDialog(Component c, String title) {
        final Dialog dialog;

        while( c!=null && !(c instanceof Frame) ) c=c.getParent();

        dialog = new Dialog((Frame)c, title,
              type==CONFIRME || type==PASSWORD || type==QUESTION);
        java.awt.Color bg = UIManager.getColor("Panel.background");
        if( bg!=null ) dialog.setBackground(bg);
        dialog.setLayout(new BorderLayout());
        setBorder(javax.swing.BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")!=null?UIManager.getColor("Component.borderColor"):java.awt.Color.GRAY));
        dialog.add(this, BorderLayout.CENTER);
        dialog.pack();
        Point p;
        try { p = c.getLocationOnScreen(); }
        catch( Exception e ) { p = new Point(400,300); }
        Dimension d = c.getSize();
        Dimension dc = getSize();
        dialog.setLocation(p.x+Math.abs(d.width/2-dc.width/2),
                                         p.y+Math.abs(d.height/2-dc.height/2));
        dialog.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent we) {
                dialog.dispose();
            }
        });
        return dialog;
    }
}
