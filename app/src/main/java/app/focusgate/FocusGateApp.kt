package app.focusgate

import android.app.Application
import app.focusgate.arm.ArmController
import app.focusgate.data.Store
import app.focusgate.service.Notifications

class FocusGateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        ArmController.restoreOnProcessStart(this)
        Notifications.createChannels(this)
    }
}
