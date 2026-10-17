SKILL.md — JMon Development Workflow
Purpose
This skill defines a repeatable workflow for a local coding agent implementing JMon, a Java terminal monitor for operating-system resources, Java processes, JVM metrics, and Spring Boot Actuator/Micrometer metrics.
Use this skill for feature work, bug fixes, refactoring, test creation, dependency changes, and project maintenance.
Operating Principles
* Work from repository evidence, not assumptions.
* Make the smallest coherent change that advances the requested outcome.
* Keep the application read-only by default.
* Separate metric collection, domain representation, and terminal presentation.
* Test behavior at the narrowest useful level.
* Report what was actually verified.
* Preserve user changes and existing project conventions.
Workflow A — Understand the Task
1. Read CLAUDE.md and this SKILL.md.
2. Inspect PROJECT_STATUS.md, if present.
3. Inspect the relevant source files, build configuration, tests, and documentation.
4. Check Git status before editing. Do not overwrite uncommitted user work.
5. Translate the request into:
    * Expected behavior.
    * Affected architectural layer or layers.
    * Acceptance criteria.
    * Likely edge cases.
    * Required tests.
6. If the task is not blocked by a consequential ambiguity, state a reasonable assumption and continue.
Do not begin by replacing the architecture or generating a large volume of code.
Workflow B — Plan the Change
Write a short implementation plan, usually 3–7 steps. Identify:
* The primary files to modify or add.
* The public interfaces or models affected.
* The tests to add or update.
* The build and test commands to run.
Keep plans proportional to the task. For a one-line fix, do not create an elaborate design document.
Workflow C — Implement a Metric Collector
When adding a new metric:
1. Confirm the metric's meaning, unit, source, and platform availability.
2. Decide whether it belongs to system metrics, process metrics, JVM metrics, or Spring Boot metrics.
3. Reuse or introduce a small interface at the appropriate boundary.
4. Return a timestamped snapshot or a clearly modeled result.
5. Represent unsupported, unavailable, permission-denied, and failed states distinctly from numeric zero.
6. Keep platform-specific logic inside the collector or adapter.
7. Avoid blocking the TUI rendering thread.
8. Add tests for normal values, edge cases, and failure behavior.
9. Document limitations and units.
For CPU utilization, verify whether the underlying API requires two samples or a delta. Do not display an invalid first sample as a trustworthy percentage.
For memory, make the distinction between physical memory, process resident memory, JVM heap, and JVM non-heap explicit.
Workflow D — Implement Spring Boot / Actuator Integration
1. Inspect the supported Actuator endpoint and metric naming conventions for the project's chosen Spring Boot compatibility target.
2. Make the endpoint configurable; do not hard-code a host, port, path, or credentials.
3. Configure connection/read timeouts and bounded request behavior.
4. Support the authentication mechanism explicitly chosen by the project; do not silently guess credentials.
5. Keep TLS verification enabled by default.
6. Parse responses defensively. Missing metrics are not zero.
7. Avoid one HTTP request per displayed value if a single endpoint response can serve the view.
8. Ensure errors do not expose passwords, tokens, or authorization headers.
9. Add mock-server or HTTP-client tests for:
    * Successful responses.
    * Missing metric names.
    * Malformed JSON.
    * Unauthorized and forbidden responses.
    * Connection timeout or refusal.
    * Server errors.
10. Document the minimum target-application configuration needed.
Never advise users to expose sensitive management endpoints publicly as a shortcut.
Workflow E — Implement TUI Features
1. Keep rendering code separate from metric collection and business rules.
2. Use deterministic view models where possible.
3. Ensure periodic refresh does not block keyboard input or rendering.
4. Prevent overlapping refresh cycles.
5. Preserve selected rows across refreshes when the underlying process still exists.
6. Handle terminal resizing and narrow layouts.
7. Provide keyboard shortcuts and discoverable help.
8. Use clear loading, empty, disconnected, and error states.
9. Avoid excessive screen flicker and unbounded history buffers.
10. Test formatting and selection logic without requiring a real terminal wherever possible.
Workflow F — Testing and Verification
Before editing, identify the relevant commands from the repository's build files and documentation.
After editing:
1. Run focused tests for changed behavior.
2. Run the broader test suite if practical.
3. Run the build/compile task.
4. Run formatting, lint, or static-analysis tasks if configured.
5. Inspect the full diff and Git status.
6. Check for accidental secrets, generated artifacts, unrelated formatting, and untracked files.
Do not claim success when a command was not run. If verification is blocked by missing tools, unavailable platform access, or network restrictions, report the exact limitation and the next command needed.
Testing Guidance
* Prefer unit tests for formatting, metric mapping, sorting, configuration validation, and domain behavior.
* Use fake collectors to test the UI and refresh scheduling deterministically.
* Use mock HTTP responses for Actuator integration.
* Avoid flaky assertions tied to live CPU utilization or timing.
* Use platform-specific integration tests only when the environment supports them.
* Test the behavior when metrics are missing or unsupported, not just the happy path.
Workflow G — Dependency Changes
Before adding, removing, or upgrading a dependency:
1. Check whether the repository already has an equivalent dependency.
2. Confirm Java/build-tool compatibility.
3. Verify the actual API and supported version.
4. Keep dependency scope as narrow as practical.
5. Avoid adding a large framework for a small utility.
6. Update dependency documentation and tests where appropriate.
7. Run the relevant build and tests.
Do not invent dependency versions or method signatures.
Workflow H — Documentation and Project Continuity
Update documentation whenever a change affects:
* Installation or launch.
* Supported platforms.
* Configuration.
* Keyboard shortcuts.
* Metric definitions or units.
* Permissions.
* Security.
* Known limitations.
If PROJECT_STATUS.md exists, update it with:
* Current milestone.
* Completed changes.
* Files or components affected.
* Verification commands and outcomes.
* Known issues or limitations.
* The next concrete step.
Keep status factual and concise. Do not mark a feature complete merely because code was written.
Workflow I — Finish and Report
The final report should contain:
1. Implemented: Concise description of behavior added or fixed.
2. Files changed: Important files and why they changed.
3. Verification: Exact commands run and their results.
4. Limitations: Anything not tested or still incomplete.
5. Next step: One concrete recommendation if useful.
Do not paste huge source files into the report unless requested. Do not state that the task is complete if the build is broken or a required check remains unverified.
Task Acceptance Checklist
Use this checklist when relevant:
*  Repository conventions and project status were inspected.
*  The change is limited to the requested scope.
*  Metric units and availability are explicit.
*  Unsupported values are not represented as zero.
*  Collection does not block the TUI.
*  Failure states are handled.
*  Tests cover the main behavior and meaningful edge cases.
*  Build and relevant tests were run, or blockers were reported.
*  Documentation and status files were updated when needed.
*  The final report distinguishes verified results from assumptions.

