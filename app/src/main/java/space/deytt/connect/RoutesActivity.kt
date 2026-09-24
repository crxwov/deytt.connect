package space.deytt.connect

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Legacy entry point retained for existing in-app links; the route UI now lives in MainActivity's pager. */
class RoutesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPrimaryTab(1)
    }
}

internal fun Activity.openPrimaryTab(tab: Int) {
    startActivity(
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_START_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
    finish()
    overridePendingTransition(0, 0)
}
