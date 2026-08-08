# Ink engine wire harness

The JVM tests include an opt-in file harness that compiles a local `.ink` page into the
same revisioned JSON consumed by the glasses renderer. It uses the test runtime so
`org.json` remains `compileOnly` for Android consumers.

From the repository root in PowerShell:

```powershell
$env:INK_SOURCE = 'E:\tmp\demo.ink'
$env:INK_DATA = 'E:\tmp\data.json'
$env:INK_DOCUMENT_OUT = 'E:\tmp\ink-document.json'
Remove-Item Env:INK_PATCH_DATA, Env:INK_PATCH_OUT -ErrorAction SilentlyContinue
.\gradlew.bat :ink-engine:test --tests 'com.anezium.rokidbus.ink.InkWireFileHarnessTest'

adb push E:\tmp\ink-document.json /data/local/tmp/ink-document.json
adb shell am broadcast -a com.anezium.rokidbus.glasses.DEBUG_INK --es mode show --es path /data/local/tmp/ink-document.json
```

To create a matched initial document and data patch, set two more variables before
running the same test. Always show the newly generated document before applying its
patch because the pair shares an engine-generated document id.

```powershell
$env:INK_PATCH_DATA = 'E:\tmp\patch-data.json'
$env:INK_PATCH_OUT = 'E:\tmp\ink-patch.json'
.\gradlew.bat :ink-engine:test --tests 'com.anezium.rokidbus.ink.InkWireFileHarnessTest'

adb push E:\tmp\ink-document.json /data/local/tmp/ink-document.json
adb push E:\tmp\ink-patch.json /data/local/tmp/ink-patch.json
adb shell am broadcast -a com.anezium.rokidbus.glasses.DEBUG_INK --es mode show --es path /data/local/tmp/ink-document.json
adb shell am broadcast -a com.anezium.rokidbus.glasses.DEBUG_INK --es mode patch --es path /data/local/tmp/ink-patch.json
adb shell am broadcast -a com.anezium.rokidbus.glasses.DEBUG_INK --es mode hide
```

Add `--ez meter true` to a `show` or `patch` broadcast to start `HudFrameMeter`; the
next hide/replacement stops it. The receiver exists only in debug APKs and requires the
caller to hold `android.permission.DUMP`.
