Unicode true
!include "MUI2.nsh"
!define APP "Astrovia"
!define UNINST "Software\Microsoft\Windows\CurrentVersion\Uninstall\Astrovia"
Name "${APP} ${VER}"
OutFile "${OUT}"
InstallDir "$PROGRAMFILES64\${APP}"
InstallDirRegKey HKLM "Software\${APP}" "InstallDir"
RequestExecutionLevel admin
SetCompressor /SOLID lzma
!define MUI_ICON "${ICON}"
!define MUI_UNICON "${ICON}"
!insertmacro MUI_PAGE_LICENSE "${LICENSE}"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_RUN "$INSTDIR\Astrovia.exe"
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Section "Install"
  SetOutPath "$INSTDIR"
  File "${SRC}\Astrovia.exe"
  File "${SRC}\astrovia.ico"
  File "${SRC}\COPYING.txt"
  File /r "${SRC}\jre"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateDirectory "$SMPROGRAMS\${APP}"
  CreateShortcut "$SMPROGRAMS\${APP}\${APP}.lnk" "$INSTDIR\Astrovia.exe" "" "$INSTDIR\astrovia.ico"
  CreateShortcut "$DESKTOP\${APP}.lnk" "$INSTDIR\Astrovia.exe" "" "$INSTDIR\astrovia.ico"
  WriteRegStr HKLM "Software\${APP}" "InstallDir" "$INSTDIR"
  WriteRegStr HKLM "${UNINST}" "DisplayName" "${APP}"
  WriteRegStr HKLM "${UNINST}" "DisplayVersion" "${VER}"
  WriteRegStr HKLM "${UNINST}" "Publisher" "kroryan"
  WriteRegStr HKLM "${UNINST}" "DisplayIcon" "$INSTDIR\astrovia.ico"
  WriteRegStr HKLM "${UNINST}" "UninstallString" "$INSTDIR\Uninstall.exe"
  WriteRegDWORD HKLM "${UNINST}" "NoModify" 1
SectionEnd

Section "Uninstall"
  RMDir /r "$INSTDIR"
  Delete "$SMPROGRAMS\${APP}\${APP}.lnk"
  RMDir "$SMPROGRAMS\${APP}"
  Delete "$DESKTOP\${APP}.lnk"
  DeleteRegKey HKLM "${UNINST}"
  DeleteRegKey HKLM "Software\${APP}"
SectionEnd
