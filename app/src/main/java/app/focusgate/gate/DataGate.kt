package app.focusgate.gate

object DataGate {                         // gate/DataGate.kt
    @Volatile private var armed = false
    @Volatile private var floorTs = Long.MAX_VALUE
    fun isOpen(): Boolean = armed         // THE gate function; nothing else decides data access
    fun armedAtTs(): Long = floorTs       // lower bound for every UsageEvents query (MAX_VALUE while closed)
    // Both writers are called only by ArmController, on the main thread:
    internal fun open(armedAt: Long) { floorTs = armedAt; armed = true }   // after commit()
    internal fun close() { armed = false; floorTs = Long.MAX_VALUE }        // BEFORE persisting/stopping
}
