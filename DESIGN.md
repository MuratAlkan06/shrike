# Design decisions

Each entry states the decision, the strongest alternative that was rejected, and why, in plain language. They are written to be quotable in an interview, so nothing here hides behind jargon.

## Module graph: shrike-core <- shrike-clients <- shrike-admin

**Decision.** Three Maven modules in one reactor. `shrike-core` holds the broker engine and depends on the JDK alone. `shrike-clients` depends on core for its protocol and codec types. `shrike-admin` depends on clients and is the only module allowed to use a framework. Nothing points back up the chain.

**Alternative rejected.** A single module with packages as the only boundary.

**Why.** A package boundary is a convention; a module boundary is compiled and checked. The maven-enforcer bannedDependencies rule on core turns "core stays JDK-only" into a build failure rather than a code-review opinion, and it keeps the engine testable without a framework on the classpath.

## Spring enters through an imported BOM, not a parent POM

**Decision.** `shrike-admin` imports `spring-boot-dependencies` as a `pom`-typed dependency with `import` scope in its own dependencyManagement, and pins `spring-boot-maven-plugin` itself.

**Alternative rejected.** Inheriting from `spring-boot-starter-parent`.

**Why.** The reactor needs its own parent for the toolchain, enforcer, and version pinning that apply to every module, and a POM has only one parent. Importing the BOM keeps Spring's version alignment where it is wanted — inside the admin module — while core and clients carry no Spring coordinate at all. The cost is that the BOM brings no plugin management, so the plugin version is pinned by hand.

## Toolchains plugin, because local Maven runs on a Java 25 JVM

**Decision.** `maven.compiler.release=21` plus `maven-toolchains-plugin` requiring vendor `temurin` version `21`, and a test that asserts `Runtime.version().feature() == 21`.

**Alternative rejected.** Relying on `release=21` alone.

**Why.** `release=21` fixes the language level and the API surface, but the build still compiles and runs tests on whatever JVM launched Maven — here a Java 25 JVM. The toolchain forces javac and the surefire fork onto a real JDK 21, so what CI tests is what a JDK 21 user runs. CI adds a class-file check for major version 65, and the build fails outright when no matching toolchain is declared, which is the loud failure the alternative lacks.

## The clock is an injected TimeSource in its own package

**Decision.** `io.shrike.core.time.TimeSource` is a single-method interface returning epoch milliseconds; `SystemTimeSource` is the only code in the broker that calls `System.currentTimeMillis()`. Components take a `TimeSource` through their constructor.

**Alternative rejected.** Calling `System.currentTimeMillis()` where the timestamp is needed, and letting tests assert a range around "now".

**Why.** A record's timestamp is data that ends up on disk, so tests should assert its exact value, not that it fell inside a window. Injection makes that possible without a single sleep, and it keeps the wall clock — the one thing a test cannot control — behind a seam that is one line long. The interface lives in `io.shrike.core.time` rather than in `io.shrike.core.log` because retention, delivery timeouts, and the network layer will all need the same clock, and none of them should have to depend on the log package to get it.
