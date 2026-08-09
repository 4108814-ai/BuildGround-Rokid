' Launches the Nexus agent daemon without a console window. The daemon refuses
' to double-start (port 8791 already bound), so running this twice is harmless.
'
' Wire it to logon with:
'   schtasks /create /tn NexusAgentd /tr "wscript.exe //B \"E:\path\to\agentd\scripts\run-hidden.vbs\"" /sc onlogon /f
Set fso = CreateObject("Scripting.FileSystemObject")
base = fso.GetParentFolderName(fso.GetParentFolderName(WScript.ScriptFullName))
CreateObject("WScript.Shell").Run """node.exe"" """ & base & "\dist\cli.js"" run", 0, False
