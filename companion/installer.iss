; Installateur Windows de Manu Play Cam Connect (Inno Setup 6)
#define AppName "Manu Play Cam Connect"
#define AppVersion "1.1.0"
[Setup]
AppId={{6B7C1E2A-4D1B-4F7E-9C2E-7A1B2C3D4E5F}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=MANU MUSIC PROD
AppPublisherURL=https://github.com/iaaccess5-collab/manu-playcam
DefaultDirName={autopf}\Manu Play Cam Connect
DefaultGroupName=Manu Play Cam
OutputDir=dist-installer
OutputBaseFilename=ManuPlayCam-Connect-Windows-Setup
SetupIconFile=assets\icon.ico
UninstallDisplayIcon={app}\Manu Play Cam Connect.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequiredOverridesAllowed=dialog
[Languages]
Name: "french"; MessagesFile: "compiler:Languages\French.isl"
[Tasks]
Name: "desktopicon"; Description: "Créer une icône sur le Bureau"; GroupDescription: "Raccourcis :"
[Files]
Source: "dist\Manu Play Cam Connect\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion
[Icons]
Name: "{group}\Manu Play Cam Connect"; Filename: "{app}\Manu Play Cam Connect.exe"
Name: "{autodesktop}\Manu Play Cam Connect"; Filename: "{app}\Manu Play Cam Connect.exe"; Tasks: desktopicon
[Run]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Manu Play Cam Connect"" dir=in action=allow program=""{app}\Manu Play Cam Connect.exe"" enable=yes profile=private"; Flags: runhidden; StatusMsg: "Autorisation réseau (Wi-Fi privé)…"; Check: IsAdminInstallMode
Filename: "{app}\Manu Play Cam Connect.exe"; Description: "Lancer Manu Play Cam Connect"; Flags: nowait postinstall skipifsilent
