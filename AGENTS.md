# Working in this repository

Android/Kotlin. Phone hub, glasses hub, a shared bus client, and the plugins under
`plugins/`. Start with `README.md` for what the product is; `plugins/AGENTS.md` covers
plugin work specifically.

## Building

This is the verified way to build and test a module:

```
./gradlew :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug -PskipCxrGlobal=true
```

Swap the module for whichever one you touched (`:phone-hub`, `:glasses-hub`,
`:bus-client`, `:plugin-relay`, …).

**`-PskipCxrGlobal=true` is only for modules that do not link against the vendor CXR
library.** It is right for the plugins. The hubs do link against it, so building
`:phone-hub` or `:glasses-hub` with that flag fails with
`Could not find com.example.cxrglobal:lib`. Build those without it:

```
./gradlew :phone-hub:testDebugUnitTest :phone-hub:assembleDebug
```

The flag works by substituting the `com.example.cxrglobal:lib` dependency with the
sibling checkout at `../CxrGlobal` (see `settings.gradle.kts`), so that directory has to
exist next to this one — including for a worktree.

Gradle 9.5.1 and the Android SDK are already installed on this machine. Java 17 is the
toolchain.

## The build environment is not yours to repair

`local.properties` holds the machine's Android SDK path. It is untracked on purpose and
**must never be modified, regenerated, or pointed somewhere else**. A fresh git worktree
may not have one; that is a setup problem, not a build problem.

Likewise, do not create a private Android SDK, a private Gradle home, or a private
dependency cache inside the working tree, and do not add such directories to the repo.

**If the build fails for an environment reason** — SDK not found, the Gradle distribution
cannot be downloaded, a cache directory is not writable, network is refused — then
**stop and say so in your report**. Do not work around it. An agent that rewrites
`local.properties` to keep a build green leaves every later build broken, and the damage
outlives the task that caused it. Reporting "I could not verify the build because X" is a
correct, useful outcome; a green build bought by breaking the machine's configuration is
not.

Never report a build or test result you did not actually observe. Paste the real tail of
the command output. If you could not run it, say that plainly.

## Conventions

- Code, comments, documentation, and commit messages are written in English.
- Commit messages describe what changed and why, in the imperative, with no AI
  attribution and no `Co-Authored-By` trailers.
- Match the surrounding code: its naming, its comment density, its idioms. Comments
  explain a constraint the code cannot show, never what the next line does.
- Prefer small, reviewable changes. Do not restyle or refactor code you were not asked
  to touch.
