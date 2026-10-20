# Releasing

**During the private preview nothing is published to Maven Central**: there is no license yet, and
Central rejects a POM without one. Preview builds are attached to GitHub releases, or published to
GitHub Packages, by the release workflow once one exists. The publishing conventions in `build-logic/`
are ready for Central when the preview becomes public.

## Versioning

The driver version mirrors the ScalarDB line it is certified against: `3.20.0-beta.1`,
`3.20.0-beta.2`, and so on, where the major and minor say "certified against ScalarDB 3.20.x" and the
rest is the driver's own numbering. Certification is per ScalarDB line, not per patch: patch releases
do not change the storage format or the protocol, so the tests run against the newest patch of the
line. A new ScalarDB minor or major gets its own certification run and its own row in the README's
compatibility table.

## Branching model

Every branch except `main` is named after the version it carries, following the same model as
[ScalarDB](https://github.com/scalar-labs/scalardb). Releases are cut from version branches, never
from `main`. Branch names carry no prefix, `3.20`; the workflows match them with the `[0-9]+` and
`[0-9]+.[0-9]+` patterns, so a name like `release/3.20` gets no CI.

Fixes land on the newest branch that needs them and are backported down the lines they apply to.
Version branches never merge back.

## Certifying a line

Set `scalardb` in `gradle/libs.versions.toml` to the newest patch of the line and run the full check
on all five engines (`./gradlew check`; the README's "Building" for the engine flags). A release is cut
only from a commit that passed it. 3.20.0-beta.1 waits for this run against ScalarDB 3.20; until 3.20
is published the tests run on 3.19.

## Cutting a release

1. Certify the line, above.
2. On the release branch, `3.20`, set `version=` in `gradle.properties` to the release version through
   a pull request, for example `3.20.0-beta.1`, and add `docs/releases/<version>.md`.
3. Tag the merged commit `v<version>` and push the tag. Build with `./gradlew build` and attach to the
   GitHub release, with the notes from `docs/releases/<version>.md`:
   - `driver/build/libs/scalardb-native-jdbc-<version>.jar`, its `-sources.jar` and `-javadoc.jar`,
     and the POM;
   - `tools/build/distributions/scalardb-native-tools-<version>.zip`;
   - `shell/build/distributions/scalardb-native-shell-<version>.zip`.

   The samples are not published. The release workflow, once it exists, checks the tag against
   `gradle.properties` and does this step.
4. Bump `version=` on the branch to the next snapshot, `3.20.0-beta.2-SNAPSHOT`, through a pull
   request.
