package app.focusgate.core

sealed class RestoreResult {
    data class OpenGate(val armedAtTs: Long) : RestoreResult()
    object PersistReboot : RestoreResult()
    object StayDisarmed : RestoreResult()
}

/** Pure process-start restore logic used by ArmController.restoreOnProcessStart. */
object ArmRestore {
    fun restore(stored: ArmState, storedBoot: BootId?, nowBoot: BootId): RestoreResult = when (stored) {
        is ArmState.Disarmed -> RestoreResult.StayDisarmed // the existing reason is kept
        is ArmState.Armed ->
            if (BootCheck.isSameBoot(storedBoot, nowBoot)) RestoreResult.OpenGate(stored.armedAtTs)
            else RestoreResult.PersistReboot
    }
}
