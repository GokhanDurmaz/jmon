TEST CONTENT

## Session record — 2026-10-24 (review remediation and static analysis)
- Review remediation completed from the earlier read-only review findings:
  - Finding A [MED]: `MemoryTrendAnalyzer` now retains leak suspicion across temporary collection gaps (`retainedSuspicion`; gap cycles report COLLECTION_DEGRADED, recovery counting pauses during gaps, suspicion expires only when the window has zero valid samples or on baseline reset). Regression tests added for gap retention, window expiry, and recovery accounting (`MemoryTrendAnalyzerTest`).
  - Finding B [MED]: `DeepDiagnostics` reports `STARTED` for exit-0 `jcmd JFR.start` with a not-yet-visible artifact (JDK chunk-flush lag), remade handle tracking/stop semantics (COMPLETED or confirmed-stop releases the handle; failed stop stays retryable), keeps failed/partial artifacts instead of silently deleting them, honors cancel-during-startup races (`cancelRequested`), and drains subprocess stdout/stderr on a bounded daemon thread to prevent pipe deadlocks. 13 tests in `DeepDiagnosticsTest` cover success, failure, start/stop lagged artifact, retryable stop, and race cases.
  - Smaller items: diagnostics-menu render guard when `diagnostics == null`; repeated diagnostic keys rejected while busy (keyboard test with a blocked fake); dead `windowSpan` wrapper removed and GC counter segment computed once; histogram failures tracked as a separate degraded-analysis error track (`recordClassCollectionProblem/Success`, wired in Tui) so successful memory samples can't clear them; wall-clock state transitions shown in the leak-detail view (`TuiRenderer.setLeakEvents`); narrow-terminal tests now assert the real width constraints (dashboard ≤ terminal width, detail view ≤ width−2); `MemorySample.processId` Javadoc explains its non-analysis role.
- Static analysis policy established: Gradle built-in Checkstyle 10.21.4 + PMD 7.9.0, minimal hand-picked rules (`config/checkstyle/checkstyle.xml`, `config/checkstyle/suppressions.xml`, `config/pmd/pmd-ruleset.xml`). `module-info.java` excluded (Checkstyle cannot parse JPMS). Task-touched unused imports removed (ClassGrowthTracker, MemoryTrendAnalyzerTest, DeepDiagnosticsTest, RealSystemMetricsCollectorTest). Targeted suppressions (with justification) only for files inside the protected staged Actuator snapshot (SpringBootMetricsClient:167 empty catch; SpringBootActuatorIntegrationTest:3 unused import). PMD `EmptyCatchBlock` uses its rule-sanctioned `allowCommentedBlocks` option for already-documented platform-variance catches. SpotBugs deliberately not added (third-party plugin risk on Gradle 9.8.1; PMD+Checkstyle satisfy the minimal policy).
- Verification: full suite `./gradlew test --rerun-tasks` clean; checkstyleMain/Test + pmdMain/Test pass (exit 0); compileJava/compileTestJava clean. Live JFR/heap-dump verification remains outstanding (mock-based tests only per prior sessions; heap dumps require explicit user-approved targets).
- Next concrete task: obtain user approval and commit the staged Actuator integration together with this session's remediation work; then remove the two targeted Checkstyle suppressions once those files are committed.

## Session record — 2026-10-24 (live Actuator verification)
- Goal: verify Spring Boot Actuator integration end-to-end against a real local Spring Boot 4.1.0 app (temp project at /var/folders/nz/hq3kwlh16rz3zwc4qd5xk6c40000gn/T/opencode/spring-live, now deleted).
- Verified live: open-mode (200 + real metric JSON; client → poller → TuiRenderer collected/rendered 5 live metrics), secured-mode (401 → PERMISSION_DENIED "HTTP 401"; actuator/secret → SUCCESS + live values), and the real TUI via `build/install/jmon/bin/jmon --pid <liveAppPid> --spring-endpoint ...` under a pty: SPRING BOOT METRICS panel polled live authenticated values (jvm.memory.used increasing per refresh) and showed PERMISSION DENIED / HTTP 401 without credentials. Boot 4.1 exposes 5 JVM metric names (no jvm.gc.count/jvm.gc.time); JMon collected only what was available.
- Cleanup: temp app killed/removed, both temporary fixture tests (LiveActuatorOpenModeTest, LiveActuatorSecuredModeTest) deleted. Note: `gradlew run` executes inside the daemon without a pty (jline falls back to dumb terminal) — use `./gradlew installDist` and run `build/install/jmon/bin/jmon` for real TUI testing.
- Final state: full suite rerun clean — 51 tests, 0 failures/errors. Tree status unchanged from before the task (same modified files + untracked WORKSPACE_EDIT_TEST.md preserved).
- Next concrete task: review the pre-existing uncommitted integration changes (Main, SpringBootMetricsClient, Tui, TuiRenderer, SpringBootMetricsPoller + tests) and commit them; optionally document the Spring Boot CLI flags in the README keyboard/help section.

## Session record — 2026-10-24 (TUI modernization, first iteration)
- Renderer (TuiRenderer): flicker-free incremental redraw (cursor-home + per-line erase instead of full clear each frame); shared single-border two-panel rows (removed the `┐│┌` triple-border clutter and redundant full-width box); ANSI-aware fitVisible that now truly truncates so narrow terminals can no longer corrupt borders; terminal-height budget that clips the class-histogram panel and shows a "(content clipped...)" notice instead of scrolling; color support detection via `max_colors` capability + `NO_COLOR` convention; percent histories plotted on a truthful absolute 0-100 scale; bytes formatting now covers B/KB/MB/GB; missing CPU/RAM/heap values render "N/A" instead of fake zeros.
- Interaction (Tui): q/Q quit, h/? help overlay (monitoring keeps running while help is shown), +/- interval 1000-60000 ms waking the render loop immediately; interval changes re-tune the Spring Boot poller via new SpringBootMetricsPoller.setPollInterval. Removed ~160 lines of dead rendering helpers and debug stderr output from Tui.java.
- CLI behavior, Actuator contract, auth/error handling, and poller lifecycle unchanged; render() signature unchanged.
- Verification: full suite `./gradlew test --rerun-tasks` = 65 tests, 0 failures/errors/skipped (14 new tests: narrow width, no-color, colors, N/A values, height clipping, help, key handling incl. clamps). Live pty runs against a local real JVM confirmed rendering, help overlay, clean q exit.
- Known remaining limitations: no panel navigation/focus (single-page dashboard); sparklines live only in renderer memory (no CLI persistence); low-width clip ellipsis may hide some content entirely on two-panel rows; Windows unchecked.

## Session record — 2026-10-24 (OBJECT & CLASS MEMORY improvements)
- New `io.jmon.system.ClassIdentity`: verbatim name preservation, module suffix parsing, reliable array descriptor decoding ([B -> byte[], [Ljava.lang.String; -> java.lang.String[], nested depths), avg shallow heap per instance (safe for zero instances), JDK classification only for named java.*/jdk.* modules — no invented identity.
- Panel rework: columns CLASS (full name when width permits, truncation keeps original prefix + ellipsis, never renamed), KND (ARR/JDK tag), INSTANCES (grouped), SHALLOW (total), AVG (per-instance or explicit n/a); responsive column dropping below width thresholds; selection marker '>' (bold, so it works without color); hint line for selection keys.
- Selection + detail view: j/DOWN and k/UP move the selection, ENTER/o toggles the detail view, b/B returns; arrow escape sequences (27 [ A/B) parsed in readInput; detail shows the verbatim name hard-wrapped left-flush so the full identity is copy/reconstructable even on narrow terminals, plus array/component types, module info, instance/avg stats, and explicit notes that shallow heap excludes referenced and retained objects and that the data source (jcmd GC.class_histogram) provides only class-level aggregates with no per-object sizes.
- Help overlay and README updated with the new keys; existing help semantics, q/Q, +/- refresh, help active behavior, Spring Boot paths, and poller lifecycle untouched.
- Verification pending-final: 21 new tests (ClassIdentityTest 7 + renderer 8 + keyboard 6); full suite run to be re-confirmed with --rerun-tasks at the end of the task.
- Limitations: width-constrained list rows still use prefix + ellipsis truncation (full name via detail view); avg shallow rounds to nearest byte; classification for non-JDK named modules intentionally not claimed.

## Session record — 2026-10-24 (memory leak detection and deep heap analysis)
- New analysis core (io.jmon.core): MemorySample (immutable, all-unavailable sentinels = -1, never fake zeros), MemoryTrendAnalyzer (deterministic engine with injectable SystemTimeSource clock), AnalysisResult/AnalysisEvent/GrowingClass/AnalysisState, DiagnosticOutcome.
- Sampling: one MemorySample per refresh cycle from the existing JvmMetricsCollector (heap used/committed/max, GC counters) plus real post-GC heap values from JvmGcMonitor notifications (timestamped at drain time, approximation documented) and verbatim class histograms routed through the new ClassGrowthTracker. Bounded history (1440 samples / 256 post-GC events), per-PID identity: a PID change or explicit reset clears the baseline so data from different processes is never combined.
- Classification: INSUFFICIENT_DATA / NORMAL / GROWTH_OBSERVED / POSSIBLE_MEMORY_LEAK / COLLECTION_DEGRADED with documented thresholds (rise = max(512 KiB/min, 0.1% heap/min) over >= 4 post-GC events spanning >= 60 s; corroboration = sustained class growth >= 1 MiB or 2x slope), least-squares post-GC slope, GC counter-reset-safe rate segments, hysteresis (3 evaluations below half threshold to downgrade), event dedup, and recovery events.
- Class-level history: ClassGrowthTracker keys on verbatim histogram names (arrays/JDK classes treated uniformly); absent classes expire with the window and are never read as zero; growth rate + sustained flag per class; explicit testable reset; manual key `r` and automatic PID-change reset.
- Deep diagnostics (io.jmon.system.DeepDiagnostics): user-triggered only via the new `d` menu — y: jcmd GC.heap_dump, j: bounded JFR (120 s cap, settings=profile), s: early stop; refuses without explicit confirmation, never overwrites artifacts, checks disk space against the committed-heap estimate, 90 s/20 s timeouts, cancel supported (x), cancellation/timeouts reported (CANCELLED/PARTIAL with any partial artifact), UNSUPPORTED distinguished from FAILED. Honest boundary: no HPROF parser bundled — retained-size/dominator analysis NOT available; dumps are artifacts for external tooling (eclipse MAT/VisualVM), explicitly shown in the menu.
- TUI: leak panel now renders the richer analysis (state color, trend, GC pressure, top-3 growing classes); `e` opens the MEMORY LEAK ANALYSIS - DETAIL view (full explanations, all leading classes with verbatim names wrapped separately from metrics); `d` diagnostics menu shows PID, output dir, size estimate, per-operation availability, impact warnings, last result; in-TUI help and README updated with new keys e/d/r/y/j/s/x. Legacy detector class removed (superseded); constructor now takes MemoryTrendAnalyzer + DeepDiagnostics.
- CLI: --leak-window-minutes (1..1440, default 10, validated with clear errors) and --diagnostics-dir via MonitorConfiguration builder; usage text updated.
- Verification: full suite `./gradlew test --rerun-tasks` = 133 tests, 0 failures/errors/skipped (new: MemoryTrendAnalyzerTest 13, ClassGrowthTrackerTest 6, DeepDiagnosticsTest 9, renderer 5, keyboard 4, config 4, Main 5). Staged Actuator snapshot untouched; WORKSPACE_EDIT_TEST.md untouched.
- Limitations: post-GC timestamps are drain-time approximations; histogram cadence fixed at 15 s; retention fixed at 1440 samples; per-object/retained analysis out of scope; deep diagnostics verified at unit level with mocked processes only (no live heap dump was generated against a real target in this session).

## Session record — 2026-10-26 (layered architecture migration completed)
- Migration continued from the partly-moved working tree and finished without destructive git operations. Final layout: `io.jmon.domain` (pure models/rules), `io.jmon.application` (use cases + ports: SystemMetricsCollector, ProcessCollector, TargetProcessCollector, ClassHistogramCollector, SpringBootMetricsClient, DiagnosticsService, JvmConnectionSource/JvmSession, plus existing JvmMetricsProvider/registry types and MonitorConfiguration), `io.jmon.infrastructure` (adapters: OshiTargetProcessCollector, RealSystemMetricsCollector/RealProcessCollector, JcmdClassHistogramCollector, JcmdDeepDiagnostics, HttpSpringBootMetricsClient, JmxGcMonitor + new AttachJvmConnectionSource/JmxJvmSession session encapsulation), `io.jmon.presentation` (Tui, TuiRenderer), `io.jmon.app` (composition root).
- New application ports created from existing call sites; MonitorConfiguration stays in `io.jmon.application` (config value object wired through every collector port). New domain record `OperationCapability` (moved out of the old DeepDiagnostics nested type).
- Dependency inversion completed: Tui no longer constructs JvmAttachService/JvmMetricsCollector/JmxGcMonitor — it receives a `JvmConnectionSource` and opens a `JvmSession` per run (session owns the JMX connector + GC monitor + JVM-metrics collector; connector is closed even when GC-pool discovery fails). JmxGcMonitor and JvmMetricsCollector are internal to the JmxJvmSession lifecycle. Tui/TuiRenderer now use domain ClassMemoryStat and domain OperationCapability; presentation imports only application/domain + JLine.
- Composition root: `Main::main` wires RealSystemMetricsCollector, AttachJvmConnectionSource, OshiTargetProcessCollector, JcmdClassHistogramCollector, MemoryTrendAnalyzer, HealthEvaluator, TuiRenderer, optional HttpSpringBootMetricsClient and JcmdDeepDiagnostics.
- module-info exports the five new packages (app/application/domain/infrastructure/presentation); old core/spring/system/tui exports removed.
- Tests migrated via git mv (history preserved): domain (ProcessMetricsTest, SystemMetricsTest, ClassIdentityTest, ClassGrowthTrackerTest), application (MemoryTrendAnalyzerTest, MonitorConfigurationTest, SystemMetricsCollectorTest, SpringBootMetricsPollerTest), infrastructure (SpringBootMetricsClientTest, SpringBootActuatorIntegrationTest, RealSystemMetricsCollectorTest, JcmdDeepDiagnosticsTest — renamed class to match), presentation (TuiKeyboardTest, TuiRendererTest). Constructor calls/mocks updated (Tui now takes 11 deps incl. JvmConnectionSource; JcmdDeepDiagnostics replaces DeepDiagnostics; HttpSpringBootMetricsClient implements the SpringBootMetricsClient port).
- ArchUnit enforcement added (archunit-junit5 1.3.0): domain has no deps on other layers or OSHI/JLine/Jackson/JMX/attach/JNA; application no deps on infrastructure/presentation; presentation no deps on infrastructure or monitoring frameworks; infrastructure no deps on presentation/app (src/test/java/io/jmon/app/ArchitectureTest.java).
- Static analysis: stale Checkstyle suppressions for the previously staged Actuator snapshot removed (files moved; both findings fixed in place — the empty catch in HttpSpringBootMetricsClient now carries an explanatory comment, unused imports deleted).
- Verification: `./gradlew test --rerun-tasks` = 148 tests, 0 failures/errors/skipped (incl. 5 ArchUnit rules); `./gradlew checkstyleMain checkstyleTest pmdMain pmdTest` clean; `./gradlew build` successful.
- Limitations / follow-ups: heap-dump/JFR deep diagnostics still verified mock-level only; `TestApp` and SpringBootActuatorIntegrationTest exercises remain in app/infrastructure test packages intentionally; README architecture section updated, hook scripts/install docs need no change; WORKSPACE_EDIT_TEST.md untouched. Not yet committed (user decision).

## Session record — 2026-10-26 (subpackage cohesion refactoring)
- Continued from the flat 4-layer layout and regrouped every package by responsibility, preserving all behavior and the Clean-Architecture directions. Final layout: `io.jmon.domain.{memory,metrics,health,diagnostics}`, `io.jmon.application` (root: MonitorConfiguration) + `.monitoring` (SpringBootMetricsPoller + legacy aggregation APIs) + `.port.out` (8 outbound ports), `io.jmon.infrastructure.{system,jvm,diagnostics,actuator}`, `io.jmon.presentation.tui` (Tui) + `.tui.view` (TuiRenderer), `io.jmon.app` (Main/TestApp/ArchitectureTest untouched).
- Notable classification decisions: MemoryTrendAnalyzer moved from application into the domain (`domain.memory`) as an IO-free clock-injected policy engine; input handling NOT extracted from `presentation.tui` because `Tui.handleKey` mutates ~10 pieces of loop/view state (documented compromise); no `port.in` package (no inbound ports exist).
- Production and test sources both regrouped (git mv where files were tracked; the port interfaces and two infrastructure classes are untracked by history and exist only at their new locations). All package declarations, imports, and module-info rewritten — module-info now exports 14 packages.
- ArchUnit rules updated to subpackage awareness (`..` package patterns) and strengthened: domain independence from all layers + OSHI/JLine/Jackson/JMX/Attach/JNA/SLF4J; application independence from infrastructure/presentation; presentation independence from infrastructure; infrastructure may not depend on presentation/app/`application.monitoring..`; only `io.jmon.app..` and `io.jmon.infrastructure..` may depend on `io.jmon.infrastructure..` (composition-root wiring).
- Checkstyle: fixed 3 post-move violations (redundant same-package import in SystemMetricsCollectorTest, unused wildcard remnant + duplicated `domain.memory.*` import in SpringBootMetricsClientTest/TuiRendererTest).
- Verification (all run, all green): `./gradlew classes`; `./gradlew test --rerun-tasks` = 148 tests, 0 failures/errors (incl. 5 ArchUnit rules + MemoryTrendAnalyzerTest 17 + TuiKeyboardTest 19); `./gradlew checkstyleMain checkstyleTest pmdMain pmdTest` clean; `./gradlew build --rerun-tasks` successful (exit 0); `git diff --check` clean; no remaining flat-package references (`io.jmon.presentation.X` / `io.jmon.infrastructure.X` / `io.jmon.application.X` / `io.jmon.domain.X` direct class refs) anywhere in src.
- README "Project Structure" table rewritten for the subpackage layout (old 5-row flat table replaced; boundary-enforcement paragraph updated with the two new rules).
- Limitations / follow-ups: deeper diagnostics still verified mock-level only; stale index state shows a few RD entries (git rename from the previous session's staging not refreshed) — informational only, no commit performed; input extraction from Tui deliberately deferred; Windows support untouched.

## Session record — 2026-10-26 (strict magic-number policy + shared constant consolidation)
- Checkstyle MagicNumber no longer globally exempts -1/0/1 (`ignoreNumbers=""`); per-line
  documented exceptions go through `SuppressWithNearbyCommentFilter` (`// magic-exempt: <reason>`).
- Sentinel/config-like literals replaced by named constants (UNKNOWN_ARTIFACT_BYTES, NO_PID,
  NO_SAMPLE_ID, MIN_BASELINE_HEAP_BYTES, NO_KEY, FIRST_MEASUREMENT, HISTOGRAM_INSTANCES_GROUP,
  MIN_POLL_SLEEP_MILLIS, ...). Negative canary verified: an unexplained operational literal
  (`512345L`) fails `checkstyleMain` with a MagicNumber ERROR (BUILD FAILED); reverted after test.
- New shared constants: `io.jmon.common.constants` (TimeConstants, DataSizeConstants,
  MonitoringConstants) consolidating PERCENT_SCALE, KIB/MIB_BYTES, NANOS_PER_MILLIS,
  MILLIS_PER_MINUTE, NANOS_PER_MINUTE, HISTOGRAM_TIMEOUT_SECONDS; consumers updated
  (MemoryTrendAnalyzer, ClassGrowthTracker, TuiRenderer, JcmdDeepDiagnostics, HealthEvaluator,
  JvmMonitoringService, JcmdClassHistogramCollector). Intentionally separate: DRAIN_JOIN_MILLIS
  vs MILLIS_PER_SECOND vs REFRESH_INTERVAL_STEP_MILLIS (equal values, different semantics).
- ArchUnit updated: canonical-package allowlist now includes `io.jmon.common..`; new rule
  `common_must_be_a_leaf_package` (common depends on no internal layer).
- Verification (all exit code 0): clean + checkstyleMain + checkstyleTest + pmdMain + pmdTest +
  test + check + build; 152/152 tests green (151 existing + 1 new ArchUnit rule); review
  profiles unchanged (reviewMain 81, reviewTest 8 report-only findings).
- Next: trim remaining magic-exempt justifications where better named constants could exist;
  enlarge ImageSize/ChangeSize suppressions still pending baseline discussion.

## Session record — 2026-10-26 (QA audit and enforcement strengthening)
- Gate effectiveness verified by controlled canaries (all reverted): a presentation→infra
  dependency and a common→domain dependency each fail the matching ArchUnit rules; a PMD canary
  fires UnusedPrivateField/UnusedLocalVariable/CloseResource/PreserveStackTrace; a Checkstyle
  canary fails. Canaries confirmed real gates, not placeholders.
- Policy gap closed: previously a magic literal in ANY field initialization passed untouched.
  Now `ignoreFieldDeclaration=false` with an xpath suppression that exempts only FINAL field
  declarations; mutable (incl. static) field literals fail — MonitorConfiguration defaults,
  Tui and TuiRenderer sentinels converted to named constants as a result.
- Real defect fixed: HealthEvaluator used String.format without Locale.ROOT — locale-dependent
  decimal separators (BREAKS alerts on tr/de/etc. locales). Caught by the new HealthEvaluatorTest
  (boundary values exactly at 80/90/95 thresholds, escalation, absent-metric branches). Formatter
  audited: TuiRenderer already used Locale.ROOT everywhere.
- New tests: HealthEvaluatorTest (10), JvmMonitoringServiceTest (5: success composition,
  provider-failure mapping, independent failure isolation, shutdown idempotence; after-shutdown
  collect is rejected). 167 total, 0 failures.
- CI: added .github/workflows/ci.yml running `clean check build` on push/PR (check = test +
  Checkstyle + strict PMD + ArchUnit + informational review PMD); release build upgraded from
  `clean test installDist` to `clean check build installDist`.
- Verified battery: clean checkstyleMain checkstyleTest pmdMain pmdTest test check build
  (EXIT 0); review tier unchanged (81 + 8 informational).
- Remaining debt: hardcoded 10 s/20 s timeouts in JvmMonitoringService not runtime-injectable
  (timeout path not unit-testable); FileLength suppressions for 4 oversized files still
  TODO(#backlog); review finds 81 report-only items pending triage.

## Session record — 2026-10-26 (timeout seams deterministically tested, CI validated, review triage)
- JvmMonitoringService now accepts injected collection timeouts (2-arg constructor keeps the
  10 s / ProductionConstants defaults; 4-arg Duration constructor for tests/config); collects
  cancel hung tasks via Future.cancel(true) on timeout; constructor validates providers/timeouts.
- New tests genuinely exercise: snapshot & histogram timeout paths (latched providers, 50 ms
  timeouts, deterministic over 3 reruns), timeout->cancellation evidence (underlying task thread
  observes its interrupted flag), caller interruption->ERROR "Interrupted" with preserved
  interrupt flag, constructor validation, post-shutdown rejection. 10 tests in this class.
- CI: exact workflow command `./gradlew clean check build` run locally, green (172 tests/
  0 failures, EXIT 0). The ci.yml is still untracked so a GitHub Actions run could not be
  observed remotely (gh only lists the existing Release JMon workflow).
- Review-tier PMD triage: CyclomaticComplexity 27-28, NcssCount 17-24, CognitiveComplexity 22,
  ExcessiveParameterList 13-15, TooManyFields 1 (all priority 3). Prioritized: MemoryTrendAnalyzer
  > Tui/TuiRenderer > JcmdDeepDiagnostics (cohesive-splits, backlogged), then record-based
  constructor cleanups. Fixed now: HealthEvaluator.checkThreshold gained a Threshold record
  (7 -> 4 params) with the parameter-list finding cleared and its remaining complexity debt noted.
- Full battery: clean check build EXIT 0; WORKSPACE_EDIT_TEST.md untouched.

## Session record — 2026-10-26 (TUI keypress refresh regression fixed)
- Reported symptom (v0.2.0): Up/Down keys made System Resources graphs jump and the Memory
  Leak Analysis "Observed ... min:" value inflate abnormally fast.
- Root cause: every key press calls wakeRenderer() -> shutdownLock.notifyAll(), and the main
  loop waited a fixed refreshMillis after each cycle; an early wake jumped straight to the top
  of the loop and ran a FULL collection cycle (system + process + JVM + heap samples) plus a
  render. Each keypress thus sampled early, appended an extra graph history sample (render()
  also appended internally) and inflated the leak "Observed min" span (calculated observation
  window in minutes, built by MemoryTrendAnalyzer.buildExplanations — not a user threshold).
- Fix: collection is now gated by the elapsed refresh interval (CollectionGate, injectable
  clock). Early wake-ups only re-render the last collected snapshot; they never collect or
  append history. History is recorded exactly once per completed collection cycle
  (renderer.updateHistory moved post-collection; render()'s internal recordHistory removed,
  making render purely observational). runLoop extracted from start() (package-private, gate
  injectable) and no longer depends on start() for running/refreshMillis init. Dead field
  latestAnalysis removed (overlays now use the same post-collection analysis value).
- Regression tests: TuiCollectionGateTest (8 unit tests, injected fake clock) and
  TuiRunLoopTest (3 loop tests with fake collectors/session/renderer and a controllable gate
  clock): 2 keyboard bursts produce 0 extra collections (fails under v0.2.0 behavior —
  verified by a temporary canary reverting the gate), collection resumes exactly after the
  interval elapses, and quit stops the loop. Battery: clean check build EXIT 0, 183 tests/
  0 failures; WORKSPACE_EDIT_TEST.md untouched. Manual interactive verification still required.
