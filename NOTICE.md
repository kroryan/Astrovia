# Notice / Attribution (desktop edition)

**Astrovia** is a derivative work of the **Aladin Sky Atlas** (<https://aladin.cds.unistra.fr/>),
© Université de Strasbourg/CNRS, developed by the Centre de Données astronomiques de Strasbourg (CDS),
distributed under the **GNU General Public License v3** (full text in [COPYING](COPYING)).
Original authors: Pierre Fernique, Thomas Boch, Anaïs Oberto, François Bonnarel, Chaitra and contributors.

## Modifications made by Astrovia

- Rebranding (name, icons, texts, `~/.astrovia` data folder) and a dark look and feel (FlatLaf + Astrovia palette).
- First-run welcome / disclaimer window and a new About window.
- Usage statistics towards the CDS and the CDS release check are disabled.
- New launcher class `cds.astrovia.Astrovia`, packaging scripts (jar, .deb, Windows installer, MSIX).
- Adapted with the help of artificial-intelligence tools.

## Not an official application

Astrovia is **not** affiliated with, endorsed by or sponsored by the CDS or the Aladin project.
"Aladin", "CDS", "Simbad" and "VizieR" belong to their respective owners and are only used to credit the
original work and data. Logos of third-party data services shown inside the application belong to their owners.

## Third-party components

| Component | Licence | Where |
|---|---|---|
| FlatLaf 3.5.4 (FormDev) | Apache-2.0 | `lib/flatlaf-3.5.4.jar` |
| ADQL, JSAMP, kXML2, CDS-HEALPix, MOC libraries | as shipped with upstream Aladin 12.x; see each project | `lib/` |
| Eclipse Temurin 21 JRE (Windows installer and portable zip only) | GPL-2.0 with Classpath Exception; notices in `jre/legal/` | bundled runtime |

## Source code

The corresponding source code of every distributed version is in this repository
(<https://github.com/kroryan/Astrovia>); each release is tagged.

## Data acknowledgements

This application makes use of the SIMBAD database and the VizieR catalogue access tool, operated at CDS,
Strasbourg, France, and of HiPS surveys served by their respective data providers; their terms of use and
citations apply.

## Archives feature (0.6.5)

The "Archives" window (desktop) and sheet (Android) are an original implementation written for Astrovia on top of
public IVOA standards (TAP/ADQL, VOTable). They query these services; their terms of use and citation requests apply:

- ESA archives (XMM-Newton, Herschel, HST, JWST, Gaia, Euclid...) through the ESA Sky archive TAP service (sky.esa.int/esasky-tap), the Gaia archive and the HST archive.
- NASA Exoplanet Archive (exoplanetarchive.ipac.caltech.edu), MAST at STScI (TESS, Kepler, K2 and other missions), IRSA at Caltech/IPAC, HEASARC at NASA/GSFC and the ESO archive.
- Outreach pictures: ESA/Webb, NASA, ESA and CSA; ESA/Hubble and NASA; the Euclid Consortium and ESA. Each picture shows its credit in the viewer. Their reuse terms apply.
- IMCCE SkyBoT (solar-system objects) and SIMBAD (CDS) for names and publications; ADS links for papers.

"ESA" and the names of the missions belong to their owners and are used only to describe the data. Astrovia is not
affiliated with or endorsed by ESA, NASA, IMCCE or the CDS.

## Image processing (0.6.5)

The image processing tools are an independent implementation (`shared/java`) of standard, well-documented algorithms
(midtones transfer function, polynomial background fitting, median / Gaussian filters, unsharp mask, Richardson-Lucy
deconvolution, sigma-clipped stacking...). Siril (https://siril.org, GPL v3) was used only as a reference for which low-level
tools an astronomy image processor offers; no Siril code is included.
