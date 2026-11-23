# Contributing

Thank you for your interest. Please read this before opening a pull request.

## Contributor licence agreement

No outside contribution is merged without a signed contributor licence agreement (`CLA.md`). It is a licence grant, not a copyright assignment: you keep your copyright. It lets the owner offer the project, including your contribution, under other terms as well as the GPL. To sign, add a comment to your pull request stating that you agree to `CLA.md` at its current commit, with your full name.

The CLA is a draft pending legal review. Until the reviewed version is in place, outside contributions cannot be merged.

## Ground rules

- No proprietary dependencies: no Google Play Services, Firebase, analytics or crash-reporting SDKs. Dependencies come only from Google Maven and Maven Central.
- `:engine` is Kotlin Multiplatform `commonMain` only, with permissively licensed dependencies (Apache-2.0, MIT, BSD).
- Every model constant lives in the engine's coefficients file, with a comment giving its status and source.
- Every source file starts with `// SPDX-License-Identifier: GPL-3.0-only` (or the comment form for its language).
- Care data does not belong in this repository; see `overgrown-data`.

## Checks

```
./gradlew build licensee :engine:compileKotlinIosArm64
```

CI also builds the release APK twice and fails if the two differ.
