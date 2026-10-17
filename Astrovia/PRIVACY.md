# Privacy Policy — Astrovia: Sky Atlas

Last updated: October 17, 2026 (version 0.6.4)

Astrovia: Sky Atlas is a free, open-source astronomy application for Android, Windows and Linux, without advertisements, analytics, tracking, or user accounts.

We respect your privacy. Astrovia: Sky Atlas is designed to operate without collecting personal information or transmitting user data to our own servers.

## 1. Data We Collect

Astrovia: Sky Atlas does not collect, store on external servers, sell, or share personal data.

Specifically, Astrovia: Sky Atlas does not use:

- User accounts or registration systems.
- Advertising networks or advertising identifiers.
- Analytics, crash-reporting or user-tracking services of its own.
- Cookies or third-party tracking technologies.
- Personal data collection or profiling systems.

## 2. Network Requests and Third-Party Services

To display astronomical images, sky surveys, catalogs, observations, and pictures, Astrovia: Sky Atlas connects directly from your device to publicly available astronomical services.

These services may include:

- HiPS (Hierarchical Progressive Surveys) servers of the CDS, ESA and other observatories, and any HiPS address you type yourself.
- SIMBAD, VizieR, the Sesame name resolver and the HiPS registry, operated by the Centre de Données astronomiques de Strasbourg (CDS).
- ESA archives: the ESA Sky archive service, the Gaia archive, the Hubble and James Webb archives of ESA, and the servers that host the ESA/Webb, ESA/Hubble and Euclid outreach pictures.
- NASA and partner archives: MAST (Space Telescope Science Institute: TESS, Kepler, K2 and other missions), IRSA (Caltech/IPAC), HEASARC (NASA/GSFC) and the NASA Exoplanet Archive.
- The ESO science archive.
- IMCCE SkyBoT, for solar-system objects.
- Any TAP service that you add yourself in the Tools section.
- NASA ADS and other web pages, which open in your web browser only when you tap a link to a paper or an object page.

Network requests may contain the coordinates and size of the field of the sky you are looking at, object names that you search, catalog or archive queries, and identifiers of the items you open or download. Opening the Archives panel automatically asks the ESA and MAST archives how many results they have in the field you are looking at; "Scan all archives" asks more services. Archives only receive these requests while you use the corresponding feature.

As with any internet connection, your IP address and technical connection information may be visible to the external servers providing this content.

Astrovia: Sky Atlas does not control the privacy practices of these third-party services. Their respective privacy policies and data terms apply to information processed by their servers.

## 3. Data Stored on Your Device

Astrovia: Sky Atlas stores certain information locally on your device to provide its functionality and improve performance.

This may include:

- Application preferences and settings.
- Cached astronomical images and HiPS tiles, and cached pictures and light curves.
- Downloaded HiPS surveys.
- Downloaded astronomical catalogs.
- Your lists of objects, the TAP services you added, and saved sessions.
- Copies of the files you open, kept in the application cache and removed automatically after about a day.
- Temporary files created while stacking pictures, deleted when the operation ends.
- Temporary files and download-related information.

All such data is stored locally on your device and is not uploaded to Astrovia-operated servers.

You can remove cached data through the application's settings, where available, or through your device's storage settings. Uninstalling Astrovia removes its app-managed local data, subject to Android's storage and backup behavior.

## 4. Files You Open and Image Processing

You can open astronomical files (FITS images and tables, VOTable, CSV and TSV) with the system file picker, with "Open with", or by sharing them to Astrovia. Astrovia: Sky Atlas does not need access to all your files for this: it reads only the files you choose.

Viewing and processing of these files (stretching, background removal, noise reduction, sharpening, star measurement, color tools, geometry, calibration and stacking) happens on your device. The files, and the pictures you produce, are never uploaded by Astrovia: Sky Atlas. Results are saved only where you choose, or shared only with the application you choose when you use the share or export functions.

## 5. Background Downloads and Foreground Services

Astrovia: Sky Atlas supports downloading astronomical surveys and catalogs in the background.

When you initiate a download by selecting Start Download, Astrovia: Sky Atlas may start an Android foreground service to help keep the download running when you leave the application or turn off your device's screen.

This functionality:

- Is activated only when you initiate a download.
- Helps prevent downloads from being interrupted when the application is no longer visible.
- Displays download progress through an Android notification when notifications are permitted.
- Provides a Stop action to allow you to stop downloads.
- Combines progress information into a single notification when multiple downloads are active.

The foreground service is used exclusively to manage user-initiated downloads and does not collect personal information or perform user tracking.

Android may restrict or terminate background activity depending on the device, operating system version, battery settings, and other system conditions.

If Android prevents the foreground service from starting, Astrovia: Sky Atlas attempts to continue the download using its normal download mechanism, without the additional protection provided by the foreground service.

### Android 15 and Later

On Android 15 and later, foreground services used for data synchronization may be subject to operating system time limits.

For applications targeting Android 15 or higher, Android generally limits these services to a total of six hours of background execution within a 24-hour period, subject to the operating system's rules.

Astrovia: Sky Atlas handles these restrictions by stopping the foreground service gracefully when required.

These restrictions are imposed by Android and are not related to personal data collection.

## 6. Notifications

Astrovia: Sky Atlas uses Android notifications to display the progress of active astronomical data downloads.

Notifications may display:

- The progress of ongoing downloads.
- The status of active transfers.
- Combined information about multiple simultaneous downloads.
- A Stop action for managing downloads.

On Android 13 and later, Astrovia: Sky Atlas requests notification permission when you initiate your first download that requires it.

You may grant or deny this permission.

If you deny notification permission:

- Astrovia: Sky Atlas does not prevent you from downloading astronomical data.
- Downloads can continue using the supported Android background mechanisms.
- Download progress notifications may not appear in the notification drawer.
- Android may still display foreground-service information in its system task manager, as required by the operating system.

You can change notification permissions at any time through your device's Android settings.

Astrovia: Sky Atlas does not use notifications for advertisements, marketing, behavioral tracking, or promotional messages.

## 7. Android Permissions

Astrovia: Sky Atlas uses Android permissions necessary to provide its astronomical features and manage downloads.

These include:

- **INTERNET**: allows Astrovia: Sky Atlas to connect to public astronomical databases, retrieve sky images, observations and pictures, and download catalogs and surveys.
- **ACCESS_NETWORK_STATE**: allows Astrovia: Sky Atlas to check network connectivity and manage network-dependent operations.
- **FOREGROUND_SERVICE**: allows Astrovia: Sky Atlas to run a foreground service to support user-initiated downloads.
- **FOREGROUND_SERVICE_DATA_SYNC**: allows Astrovia: Sky Atlas to use the Android foreground service type intended for data transfers, including downloading astronomical surveys and catalogs.
- **POST_NOTIFICATIONS** (Android 13 and later): allows Astrovia: Sky Atlas to display notifications showing download progress and download controls.

Notification permission is optional. Denying it does not prevent the application's core astronomical features from functioning.

Astrovia: Sky Atlas does not request access to your location, camera, microphone, contacts, or messages. It declares that it can open FITS, VOTable and CSV files and `astrovia://` links; this is not a permission and gives the application no access to your data.

These permissions are used for application functionality and not for collecting, tracking, or sharing personal information.

## 8. Data Sharing

Astrovia: Sky Atlas does not sell, rent, or share personal information with advertisers, analytics providers, or data brokers.

Astronomical requests are sent directly to the external services necessary to provide the content you request.

Astrovia does not operate a backend service that receives or stores your personal information.

If you use a share or export function, the content goes only to the application or place you choose.

## 9. Children's Privacy

Astrovia: Sky Atlas does not knowingly collect personal information from anyone, including children.

The application does not require account creation and does not contain advertising or analytics systems.

Astronomical network requests are subject to the privacy practices of the external services described above.

## 10. Data Retention and Deletion

Astrovia: Sky Atlas does not maintain personal information on its own servers.

Locally stored settings, cached images, downloaded surveys, catalogs, lists and sessions remain on your device until removed by you, the application, or the operating system.

You can manage or delete locally stored information through available application settings, Android storage controls, or by uninstalling Astrovia, subject to Android's storage and backup behavior.

## 11. Desktop Versions (Windows and Linux)

The Windows and Linux versions follow the same principles: no accounts, no advertisements, no analytics and no tracking. The usage statistics and update checks of the original Aladin software are disabled in Astrovia.

The desktop versions connect to the same public astronomical services described in section 2 and store their local data (settings, caches, lists, downloaded and processed files) in a folder named `.astrovia` in your user folder. You can delete that folder at any time; uninstalling the program does not send any information anywhere.

## 12. Open-Source Transparency

Astrovia: Sky Atlas is an open-source application.

Its source code is publicly available on GitHub, allowing users and developers to inspect its implementation and review how the application handles network connections, downloads, permissions, and local storage.

Source code: https://github.com/kroryan/Astrovia

## 13. Changes to This Privacy Policy

This Privacy Policy may be updated to reflect changes in Astrovia: Sky Atlas's functionality, platform requirements, or applicable privacy practices.

Any changes will be published on this page with an updated revision date.

## 14. Contact

If you have questions about this Privacy Policy or Astrovia: Sky Atlas's privacy practices, you can contact the project maintainers by opening an issue on GitHub.

https://github.com/kroryan/Astrovia/issues

Astrovia: Sky Atlas — Open-source astronomy, with privacy by design.
