package dev.adrianjost.lightbar

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Invisible entry point for apps that can only launch activities or shortcuts
 * (e.g. Button Mapper): shows or hides the overlay and finishes immediately.
 *
 * It also answers [Intent.ACTION_CREATE_SHORTCUT], so it shows up in such apps' "Shortcuts"
 * list as "Toggle LightBar" and hands back a shortcut that launches this activity.
 *
 * Prefer the broadcast [LightBarService.ACTION_TOGGLE] where available, since starting an
 * activity briefly interrupts the app in the foreground.
 */
class ToggleActivity : Activity() {
    @Suppress("DEPRECATION") // The legacy shortcut extras are what button-mapping apps read.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_CREATE_SHORTCUT) {
            val launch = Intent(Intent.ACTION_MAIN)
                .setClass(this, ToggleActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(Intent.EXTRA_SHORTCUT_INTENT, launch)
                    .putExtra(Intent.EXTRA_SHORTCUT_NAME, getString(R.string.toggle_label))
                    .putExtra(
                        Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                        Intent.ShortcutIconResource.fromContext(this, R.mipmap.ic_launcher),
                    ),
            )
        } else {
            LightBarService.instance?.toggle()
        }
        finish()
    }
}
