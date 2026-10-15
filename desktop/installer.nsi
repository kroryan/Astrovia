Unicode true
!include "MUI2.nsh"
!include "FileFunc.nsh"
!include "x64.nsh"
!include "LogicLib.nsh"
!define MIN_FREE_MB 400
!define MUI_CUSTOMFUNCTION_ABORT AstroAbort
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

; Exit codes: 0 ok | 1602 cancelled | 1638 already installed | 1618 another install running
;             112 disk full | 1625 rejected by policy (not admin) | 1603 fatal error | 3010 reboot (never needed)
Function .onInit
  System::Call 'kernel32::CreateMutex(p 0, i 1, t "AstroviaSetupMutex") p .r1 ?e'
  Pop $2
  ${If} $2 = 183
    SetErrorLevel 1618
    Abort
  ${EndIf}
  UserInfo::GetAccountType
  Pop $0
  ${If} $0 != "Admin"
    SetErrorLevel 1625
    Abort
  ${EndIf}
  ReadRegStr $0 HKLM "${UNINST}" "DisplayVersion"
  ${If} $0 == "${VER}"
    ${If} ${Silent}
      SetErrorLevel 1638
      Abort
    ${EndIf}
  ${EndIf}
  ${GetRoot} "$PROGRAMFILES64" $1
  ${DriveSpace} "$1\" "/D=F /S=M" $2
  ${If} $2 < ${MIN_FREE_MB}
    SetErrorLevel 112
    Abort
  ${EndIf}
FunctionEnd

Function AstroAbort
  SetErrorLevel 1602
FunctionEnd

Function .onInstFailed
  SetErrorLevel 1603
FunctionEnd

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
