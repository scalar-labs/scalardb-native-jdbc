import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * How many per-engine integration test tasks run at once across the build: each starts its own
 * database container, and Docker's memory bounds how many fit side by side.
 */
abstract class EngineSlots : BuildService<BuildServiceParameters.None>
