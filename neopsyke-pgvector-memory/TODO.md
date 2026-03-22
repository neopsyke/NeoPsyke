# TODO

This file tracks the remaining extraction and publishing work for
`neopsyke-pgvector-memory` once it is moved into its own repository.

## GitHub Actions CI

Add a provider-local CI workflow under `.github/workflows/ci.yml`.

Goals:

- run on pull requests and pushes to the default branch
- build the project
- run all non-live tests
- fail on regressions without requiring external provider tokens

Recommended workflow shape:

1. Checkout repository
2. Set up JDK 21
3. Restore/cache Gradle dependencies
4. Validate wrapper
5. Run:
   - `./gradlew --no-daemon test`
   - optionally `./gradlew --no-daemon releaseBundleZip`
6. Upload useful artifacts on failure:
   - `build/reports/tests/test`
   - `build/test-results/test`

Recommended extra checks:

- `./gradlew --no-daemon checkKotlinGradlePluginConfigurationErrors`
- shell syntax check for:
  - `scripts/run-http-provider.sh`
  - `scripts/run-mcp-provider.sh`

Do not add live/provider-token tests to default CI:

- no real embedding API calls
- no paid external tokens
- no mandatory Docker startup in the default test workflow

If later adding Docker-backed integration tests, keep them in a separate manual
or nightly workflow, not the default PR gate.

## Release Publishing

Add a release workflow under `.github/workflows/release.yml`.

Recommended responsibilities:

- trigger on version tags
- run `./gradlew --no-daemon test fatJar releaseBundleZip`
- attach release artifacts:
  - `build/libs/neopsyke-pgvector-memory-<version>-all.jar`
  - `build/distributions/neopsyke-pgvector-memory-<version>-bundle.zip`
- publish release notes from `CHANGELOG.md`

## Stronger Release Checksum / Signing Story

Add a stronger artifact integrity story before wider public distribution.

Recommended steps:

1. Generate checksums for every published artifact
   - `SHA256SUMS` file covering the fat jar and release bundle zip
2. Attach checksum file to GitHub releases
3. Document checksum verification in `README.md`
4. Add automated release-step verification that checks generated checksums match
   the uploaded artifacts

Nice-to-have stronger follow-up:

5. Sign release artifacts or checksum manifests with GPG or Sigstore
6. Publish signature files alongside release assets
7. Document how users verify both checksum and signature

Minimum acceptable first OSS release:

- release artifacts
- SHA-256 checksums
- verification instructions

Better later release posture:

- signed release assets
- provenance/attestation

