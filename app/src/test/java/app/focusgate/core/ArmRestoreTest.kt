package app.focusgate.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ArmRestoreTest {
    private val boot1 = BootId(1, 1_000, 10_000)
    private val boot2 = BootId(2, 5_000_000, 2_000)

    @Test fun armed_newBoot_persistReboot() {
        assertEquals(RestoreResult.PersistReboot, ArmRestore.restore(ArmState.Armed(1, 1_000, 42), boot1, boot2))
    }

    @Test fun disarmedPanic_newBoot_staysDisarmed() {
        assertEquals(RestoreResult.StayDisarmed, ArmRestore.restore(ArmState.Disarmed(DisarmReason.PANIC), boot1, boot2))
    }

    @Test fun armed_sameBoot_opensGateWithStoredArmedAt() {
        assertEquals(RestoreResult.OpenGate(42), ArmRestore.restore(ArmState.Armed(1, 1_000, 42), boot1, boot1.copy(elapsedAtSave = 20_000)))
    }

    @Test fun armed_missingBootId_persistReboot() {
        assertEquals(RestoreResult.PersistReboot, ArmRestore.restore(ArmState.Armed(1, 1_000, 42), null, boot1))
    }
}
