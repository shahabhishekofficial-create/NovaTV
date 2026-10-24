package tv.nova.player.ui

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import tv.nova.player.R

class HomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = getString(R.string.welcome)
            textSize = 24f
            setPadding(48, 48, 48, 48)
        })
    }
}
