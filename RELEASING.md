# Releasing

**During the private preview nothing is published to Maven Central**: there is no license yet, and
Central rejects a POM without one. Preview builds are attached to GitHub releases, or published to
GitHub Packages, by the release workflow once one exists. The publishing conventions in `build-logic/`
are ready for Central when the preview becomes public.

## Versioning

The driver version mirrors the ScalarDB line it is certified against: `3.19.0-alpha.1`,
`3.19.0-alpha.2`, and so on, where the major and minor say "certified against ScalarDB 3.19.x" and the
rest is the driver's own numbering. Certification is per ScalarDB line, not per patch: patch releases
do not change the storage format or the protocol, so the tests run against the newest patch of the
line. A new ScalarDB minor or major gets its own certification run and its own row in the README's
compatibility table.

## Branching model

Every branch except `main` is named after the version it carries, following the same model as
[ScalarDB](https://github.com/scalar-labs/scalardb). Releases are cut from version branches, never
from `main`. Branch names carry no prefix, `3.19`; the workflows match them with the `[0-9]+` and
`[0-9]+.[0-9]+` patterns, so a name like `release/3.19` gets no CI.

Fixes land on the newest branch that needs them and are backported down the lines they apply to.
Version branches never merge back.

## Cutting a release

1. On the release branch, set `version=` in `gradle.properties` to the release version through a pull
   request, for example `3.19.0-alpha.1`.
2. Tag the merged commit `v<version>` and push the tag. The release workflow, once it exists, checks
   the tag against `gradle.properties`, builds, and attaches the artifacts.
3. Bump `version=` on the branch to the next snapshot, `3.19.0-alpha.2-SNAPSHOT`, through a pull
   request.
