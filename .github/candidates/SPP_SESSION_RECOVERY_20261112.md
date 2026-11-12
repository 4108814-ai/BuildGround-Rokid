# SPP session recovery candidate

Patch SHA256: 0f405f9f4abd096ce5d5f6b0a465185c2b4eb5e38bb13e78845a1c2b008a3162.

This is a patch asset, not a replacement runtime tree. Base branch is
candidate/nexus-spp-diagnostic-20261101 at 0552dad2d93d2c070a7ffb653c1550d37c15382f.
Its ordinary source tree lacks both session-owner helpers. Applying directly there
is expected to fail. Do not upload four composite runtime files over that tree.

Reconstruct the already reviewed diagnostic composite in the existing pipeline:

1. Phone base 060797bbae975e95ca6a22c88378e6fadec58640. From diagnostic candidate
   0552dad apply nexus-phone-local-apk.patch, nexus-phone-spp-session-owner.patch,
   then Phone portions (-p2) of nexus-phone-cxr-fold.patch and
   nexus-phone-single-owner.patch. Their CXR portions apply to existing sibling
   CxrGlobal bfc3fab18268d6dd4b2c371416189cb19610b631, as in the prior pipeline.
2. Glasses base 5fc9338668c18f6bdfab5ebdffbf602e57c96e14. Run the exact existing
   generators lifecycle-1418, native-recording-1420/1421/1422,
   notice-viewport-1423, notice-wake-1424, autostart-1425/1426,
   bootkeeper-1427/1428, spp-keeper-1429, recorder-wave-1430.
   Apply nexus-spp-v2.patch then nexus-launcher-navigation.patch from
   6f84ccdec3d20ee8e3444666c54e64eaedd50960.
3. Combine Glasses module into Phone workspace, then apply nexus-spp-diagnostic.patch
   from 0552dad. This is the previous successful diagnostic build composition.
4. Check all four baseline SHA256 values in spp-session-recovery-20261112.json.
   Then `git apply --check nexus-spp-session-recovery-20261112.patch` and apply it.
   Verify the four resulting SHA256 values. No permission or signature changes.

The local clean reconstruction matched all four baseline hashes, accepted this
patch, and matched all four previously compiled final files. The production source
is unchanged from the reviewed local candidate, so its Android compile result
remains applicable. This staging step does not sign, package or install anything.

Offline regression command, after applying to the generated composite:

```text
python .github/candidates/run-spp-session-recovery.py --java <existing-java> --compiler-classpath <existing-Kotlin-compiler-and-runtime-classpath> --project <generated-composite> --output <isolated-test-output>
```

This runner only compiles the two production ownership helpers and test fixtures.
It downloads nothing and executes no Android/device actions. Expected: 20 PASS.
SppSessionRecoveryBaseline.kt is the original Phone owner in a test-only package;
the first test reproduces its late-publication bug. Never include fixtures in APKs.

Limitations: no hardware validation or installed Glasses identity is established.
Two permanently blocked native closes exhaust bounded retirement capacity;
a permanently blocked callback delays status notifications, but not session
ownership or new-reader admission. An in-progress blocking native Phone connect
is not cancelled; publication is fenced when it eventually returns. No causal
claim is made that these defects explain all observed worn/sleep failures.
