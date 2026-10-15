// Copyright (C) 2023 Universite de Strasbourg/CNRS (CDS) - original Aladin Sky Atlas
// Astrovia is an independent community project (kroryan), distributed under GNU GPL v3.
package cds.astrovia;

/** Astrovia entry point: java -jar Astrovia.jar */
public final class Astrovia {
   private Astrovia() { }

   public static void main(String[] args) {
      cds.aladin.Aladin.main(args);
   }
}
