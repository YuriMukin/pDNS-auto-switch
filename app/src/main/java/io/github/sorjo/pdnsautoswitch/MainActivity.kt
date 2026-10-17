package io.github.sorjo.pdnsautoswitch

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val content = findViewById<android.view.View>(R.id.main_content)
        val padding = (24 * resources.displayMetrics.density).toInt()
        content.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            view.setPadding(
                padding + bars.left, padding + bars.top,
                padding + bars.right, padding + bars.bottom
            )
            insets
        }
        content.requestApplyInsets()
    }
}
