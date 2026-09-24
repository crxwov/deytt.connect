package space.deytt.connect

import android.app.Activity
import android.os.Bundle

/** Legacy entry point retained for existing in-app links; settings now live in MainActivity's pager. */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPrimaryTab(3)
    }
}
