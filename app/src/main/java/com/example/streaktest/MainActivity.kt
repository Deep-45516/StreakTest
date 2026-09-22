package com.example.streaktest

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 100, 48, 48)
        }

        val title = TextView(this).apply {
            text =
                "Streak Test\n\n" +
                "Target: Krisha (krisharkive2)\n" +
                "Shortcut: 💯\n\n" +
                "The black image will be placed in Camera Roll.\n" +
                "The automation will use Snapchat's Snap/Memories flow."
            textSize = 19f
            setPadding(0, 0, 0, 40)
        }

        val settingsButton = Button(this).apply {
            text = "OPEN ACCESSIBILITY SETTINGS"

            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            }
        }

        val sendButton = Button(this).apply {
            text = "SEND AUTOMATED TEST"
            textSize = 18f

            setOnClickListener {
                startSnapTest()
            }
        }

        layout.addView(title)
        layout.addView(settingsButton)
        layout.addView(sendButton)

        setContentView(layout)
    }

    private fun startSnapTest() {

        /*
         * Put the fixed black image into the device's shared
         * Pictures collection so Snapchat can see it as a
         * genuine Camera Roll image.
         */
        val imageUri = addBlackImageToCameraRoll()

        if (imageUri == null) {
            return
        }

        /*
         * Give MediaStore/Android a moment to publish the image.
         */
        handler.postDelayed({

            try {
                val launchIntent =
                    packageManager.getLaunchIntentForPackage(
                        "com.snapchat.android"
                    )

                if (launchIntent != null) {

                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )

                    startActivity(launchIntent)
                }

            } catch (_: Exception) {
                // Nothing else to do here.
            }

        }, 1500L)
    }

    private fun addBlackImageToCameraRoll(): android.net.Uri? {

        val resolver = contentResolver

        val fileName =
            "StreakTest_${System.currentTimeMillis()}.png"

        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                fileName
            )

            put(
                MediaStore.Images.Media.MIME_TYPE,
                "image/png"
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "Pictures/StreakTest"
                )

                put(
                    MediaStore.Images.Media.IS_PENDING,
                    1
                )
            }
        }

        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY
                )
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

        val uri = resolver.insert(
            collection,
            values
        ) ?: return null

        try {

            resolver.openOutputStream(uri).use { output ->

                if (output == null) {
                    resolver.delete(uri, null, null)
                    return null
                }

                resources
                    .openRawResource(R.drawable.streak_dark)
                    .use { input ->
                        input.copyTo(output)
                    }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                val finishedValues =
                    ContentValues().apply {
                        put(
                            MediaStore.Images.Media.IS_PENDING,
                            0
                        )
                    }

                resolver.update(
                    uri,
                    finishedValues,
                    null,
                    null
                )
            }

            return uri

        } catch (_: Exception) {

            resolver.delete(
                uri,
                null,
                null
            )

            return null
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
