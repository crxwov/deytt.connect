package space.deytt.connect

import android.app.Activity
import android.os.Bundle

/** Legacy entry point retained for existing in-app links; profile content now lives in MainActivity's pager. */
class ProfileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPrimaryTab(2)
    }
}
