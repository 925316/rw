### The 32-bit build cannot be attached

The Windows build ships two JVMs: `jvm64`, a 64-bit JDK 13, and `jvm`, a 32-bit Java 8
runtime. Two separate limits make the 32-bit build unattachable:

- it has no attach support at all - the runtime is a JRE, so neither `attach.dll` nor
  `tools.jar` is present;
- the attach API refuses to mix architectures, so a 64-bit JVM can never reach a 32-bit
  process either.

So the 32-bit launcher can only be reached by restarting the game with the agent, which is
exactly what `rw.bat` does for you: it uses `jvm64` when it exists, so the game that ends
up running is the 64-bit one. Starting the 64-bit launcher yourself lets `rw.bat` attach
without a restart.


Requirements: the JVMs shipped with the game. A `JAVA_HOME` or `PATH` JVM is only used to
run the injector when the game folder has no `jvm64`.

## Build

`build.bat` compiles `src/` with the game's `javac` and writes `build\rw.jar`. `rw.bat`
looks for `rw.jar` next to itself first, then in `build\`, so a self-compiled build runs
straight from the source tree. The release workflow builds the same jar, packs `rw.jar`
and `rw.bat` into `rw.zip`, and republishes the `latest` release on every push to master,
so there is always one download URL and no version numbers to track. Each build records
its commit in `build-stamp.txt` inside the jar, which the agent logs as `build=...`.