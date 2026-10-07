; Ghostly VPN — Windows installer (Inno Setup 7).
; A plain EXE installer on purpose: no MSI / Windows Installer service, no admin rights,
; works on trimmed Windows builds too. Build: tools/windows/build-installer.ps1
#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\..\desktopApp\build\compose\binaries\main\app\Ghostly"
#endif

[Setup]
AppId={{6B0D7F3E-2F5C-4F3C-9D44-6A0C5A8F1E21}
AppName=Ghostly VPN
AppVersion={#AppVersion}
AppVerName=Ghostly VPN {#AppVersion}
AppPublisher=Ghostly
AppPublisherURL=https://ghostlynex.fun
AppSupportURL=https://github.com/Nelxi/ghostly-vpn
AppUpdatesURL=https://github.com/Nelxi/ghostly-vpn/releases
DefaultDirName={localappdata}\Programs\Ghostly
DefaultGroupName=Ghostly VPN
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\..\build\release
OutputBaseFilename=Ghostly-Windows
SetupIconFile=..\..\desktopApp\icons\ghostly.ico
UninstallDisplayIcon={app}\Ghostly.exe
UninstallDisplayName=Ghostly VPN
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes
RestartApplications=no
LicenseFile=..\..\LICENSE

[Languages]
Name: "ru"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
Name: "autostart"; Description: "Запускать Ghostly вместе с Windows"; GroupDescription: "Дополнительно:"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Ghostly VPN"; Filename: "{app}\Ghostly.exe"
Name: "{group}\Удалить Ghostly VPN"; Filename: "{uninstallexe}"
Name: "{userdesktop}\Ghostly VPN"; Filename: "{app}\Ghostly.exe"; Tasks: desktopicon

[Registry]
; ghostly:// links from the site and subscription pages open the app
Root: HKCU; Subkey: "Software\Classes\ghostly"; ValueType: string; ValueName: ""; ValueData: "URL:Ghostly VPN"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Classes\ghostly"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""
Root: HKCU; Subkey: "Software\Classes\ghostly\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: """{app}\Ghostly.exe"",0"
Root: HKCU; Subkey: "Software\Classes\ghostly\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Ghostly.exe"" ""%1"""
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Ghostly"; ValueData: """{app}\Ghostly.exe"" --autostart"; Tasks: autostart; Flags: uninsdeletevalue

[Run]
Filename: "{app}\Ghostly.exe"; Description: "{cm:LaunchProgram,Ghostly VPN}"; Flags: nowait postinstall skipifsilent
; self-update runs the installer silently — bring Ghostly back afterwards
Filename: "{app}\Ghostly.exe"; Flags: nowait skipifnotsilent

[UninstallRun]
; Close Ghostly gracefully first: it disconnects and restores the system proxy on exit.
Filename: "{sys}\taskkill.exe"; Parameters: "/IM Ghostly.exe"; Flags: runhidden waituntilterminated; RunOnceId: "CloseGhostly"
; Then stop only OUR xray (other VPN clients run xray.exe too — never touch theirs).
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -Command ""Start-Sleep 2; Get-Process Ghostly,xray -ErrorAction SilentlyContinue | Where-Object {{ $_.Path -like '{app}*' }} | Stop-Process -Force"""; Flags: runhidden waituntilterminated; RunOnceId: "StopOurXray"

[UninstallDelete]
Type: filesandordirs; Name: "{app}"
