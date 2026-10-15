# Astrovia for Windows – installer return codes

Installer: `Astrovia-<version>-windows-setup.exe` (NSIS, per-machine install, administrator rights required).

- Silent install: `Astrovia-<version>-windows-setup.exe /S`
- Custom folder: `... /S /D=C:\Path\Astrovia` (`/D` must be the last argument)
- Silent uninstall: `"%ProgramFiles%\Astrovia\Uninstall.exe" /S`
- Installed-app detection: `HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall\Astrovia`

Return codes use the standard Windows values, so they match
[Windows Installer error codes](https://learn.microsoft.com/windows/win32/msi/error-codes)
and [system error codes](https://learn.microsoft.com/windows/win32/debug/system-error-codes).

## Standard scenarios

| Scenario | Code | Meaning |
|---|---|---|
| Installation successful | `0` | Installed correctly. |
| Installation cancelled by user | `1602` | The user cancelled the setup. |
| Application already exists | `1638` | The same version is already installed (silent mode). |
| Installation already in progress | `1618` | Another Astrovia setup is running; finish it first. |
| Disk space is full | `112` | Less than 400 MB free on the system drive. Free space and retry. |
| Reboot required | `3010` | Declared for completeness; Astrovia never needs a reboot. |
| Network failure | `12029` | Reserved. The installer is offline (it bundles its Java runtime). |
| Package rejected by policy | `1625` | Not run as administrator, or blocked by a device policy. |

## Miscellaneous failure scenarios

| Scenario | Code | Cause and fix |
|---|---|---|
| Fatal error during installation | `1603` | A file could not be copied or written (antivirus, permissions, locked file). Close other programs, disable interfering software and retry as administrator. |
| Elevation declined (UAC) | `1223` | The user rejected the administrator prompt (returned by Windows). Run again and accept the prompt. |
| Installer aborted unexpectedly | `2` | The setup script aborted. Re-download the installer and verify it against `SHA256SUMS.txt`. |
| Invalid command-line parameter | `1639` | A wrong switch was passed. Use only `/S` and `/D=<path>`. |

## Documentation references

- Windows Installer error codes: https://learn.microsoft.com/windows/win32/msi/error-codes
- System error codes (1000–1299, includes 1223): https://learn.microsoft.com/windows/win32/debug/system-error-codes--1000-1299-
- System error codes (0–499, includes 112): https://learn.microsoft.com/windows/win32/debug/system-error-codes--0-499-
- NSIS exit codes and silent switches: https://nsis.sourceforge.io/Docs/Chapter3.html#installerusagecommandline

## Support

Report problems at https://github.com/kroryan/Astrovia/issues
