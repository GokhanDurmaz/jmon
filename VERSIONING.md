# JMon Versioning and Release Policy

This document is the authoritative versioning and release policy for JMon.
It records the conventions established by the repository's actual release
automation: tag-triggered GitHub Actions, `vMAJOR.MINOR.PATCH` tags, and
artifact versions derived from the tag itself.

## 1. Semantic Versioning

JMon uses `vMAJOR.MINOR.PATCH` release tags (for example `v0.2.0`).

- **PATCH** (example `v0.2.0` -> `v0.2.1`): backward-compatible bug fixes,
  regression fixes, security fixes, and small corrections.
- **MINOR** (example `v0.2.1` -> `v0.3.0`): backward-compatible new features
  and significant enhancements.
- **MAJOR**: breaking changes, or an explicitly approved transition to a
  stable public release, following the project's compatibility and pre-1.0
  policy.

While JMon remains at `0.x`, public interfaces and behavior may still
evolve. Version numbers classify the nature of a change (fix, feature,
break), but the project does not yet promise broad backward-compatibility
guarantees across MINOR releases.

### 1.1 Security Patch Policy

Security fixes use the same `vMAJOR.MINOR.PATCH` numbering. No special
suffixes such as `v0.2.1-security` are used.

- A backward-compatible security fix normally increments PATCH.
- A release containing substantial backward-compatible new functionality
  may require a MINOR increment. Avoid bundling unrelated features into
  urgent security releases.
- A security fix requiring breaking changes follows the versioning decision
  for the project's current pre-1.0 policy.
- Multiple related security fixes may be combined into one release when
  appropriate.

Version numbering is separate from release urgency. Urgency depends on
impact, exploitability, exposure, affected versions, and available
mitigations - not on the size of the version increment. Critical or
actively exploited issues receive expedited handling; high-severity issues
are prioritized; medium and low severity issues are scheduled by risk.

Workflow for a security release:

1. Assess impact, exposure, affected versions, and supported branches.
2. Implement the smallest effective fix and add regression tests.
3. Review changes and logs for accidental disclosure of credentials,
   tokens, secrets, or sensitive vulnerability details.
4. Run the required validation described below and document unavoidable
   limitations.
5. Commit and push without rewriting shared history.
6. Verify branch CI and the release automation before tagging.
7. Tag the correct validated commit with an unused version.
8. Publish release notes without prematurely disclosing exploit-enabling
   details.
9. Consider a GitHub Security Advisory or coordinated disclosure where
   appropriate and supported.

Ordinary bugs and regressions (including the TUI refresh regression fixed
for `v0.2.1`) are not classified as security issues unless the
investigation establishes a genuine vulnerability.

## 2. Version Derivation and Metadata

Verified behavior of the current build and release automation:

- `build.gradle` derives the project version from the Gradle property
  `releaseVersion`: `version = providers.gradleProperty('releaseVersion')
  .orElse('0.0.0-dev').get()`. Local builds without the property use the
  `0.0.0-dev` placeholder.
- `.github/workflows/release.yml` derives the version from the pushed tag:
  `VERSION="${GITHUB_REF_NAME#v}"`, passed to Gradle as
  `-PreleaseVersion="$VERSION"`.
- Consequence: **no manual version metadata update is required for a
  release.** The artifact version always matches the `vMAJOR.MINOR.PATCH`
  tag name.

The release workflow runs `clean check build installDist` (tests,
Checkstyle, strict PMD, ArchUnit, review PMD, and the distribution build),
exercises the install image, and publishes the release assets
`jmon.tar.gz`, `jmon.tar.gz.sha256`, `jmon-windows.zip`,
`jmon-windows.zip.sha256` via `softprops/action-gh-release@v2` with
generated release notes.

## 3. Release History

- `v0.2.0` - previous published release (Spring Boot Actuator monitoring,
  layered architecture, memory analysis, diagnostics, QA gates).
- `v0.2.1` - previous published release
  (https://github.com/GokhanDurmaz/jmon/releases/tag/v0.2.1): patch
  regression fix for the TUI refresh schedule (keyboard wake-ups
  triggering unscheduled metrics collection), verified by branch CI and
  the tag-triggered release workflow.
- `v0.3.0` - current published release
  (https://github.com/GokhanDurmaz/jmon/releases/tag/v0.3.0): self-update
  feature (`jmon --update`) with SHA-256 checksum verification, validated
  extraction and an atomic install swap, verified by branch CI and the
  tag-triggered release workflow.

## 4. Standard Release Workflow

1. Implement the change and add regression tests.
2. Run focused tests and the complete required local verification
   (`./gradlew clean check build`).
3. Review the diff and commit only the intended files; never use
   `git add .` or `git add -A`.
4. Push the commit to the intended branch (`main`) with a normal push.
5. Verify branch CI (`ci.yml`) succeeds for that exact commit.
6. Create the version tag on the exact validated commit and push it.
7. Verify the tag-triggered release workflow (`release.yml`) succeeds.
8. Verify the published GitHub Release page and its assets.

Rules:

- Never reuse, overwrite, force-update, or silently delete a published tag
  or release.
- Distinguish local test success, branch CI success, tag-triggered release
  success, and actual publication.
- Do not reuse a version number that exists locally or remotely; if a tag
  collides, stop and use the next appropriate unused version.
- Documentation updates that follow a release are made in separate normal
  commits; the release tag stays on the validated release commit.

Every future release must follow this policy. Update this document when
the verified release automation or policy changes; avoid speculative
details.
