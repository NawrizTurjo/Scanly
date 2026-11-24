package dev.turjo.paperless.feature.settings

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle

class UpdateReleaseFixtureInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val assets = """
                {"name":"scanly-x86.apk","browser_download_url":"https://github.com/wrong","size":12},
                {"name":"scanly-arm64.apk","browser_download_url":"https://github.com/right","size":123}
            """.trimIndent()
            val release = parseUpdateRelease("""{"tag_name":"v1.2.3","body":"First\nSecond","assets":[$assets]}""")
            check(release == UpdateRelease("1.2.3", "First\nSecond", "https://github.com/right", 123))
            val missing = runCatching {
                parseUpdateRelease("""{"tag_name":"v1.2.3","body":null,"assets":[]}""")
            }.exceptionOrNull()
            check(missing is UpdateException && missing.messageResource == R.string.update_no_asset)
            result.putString("stream", "Update release fixtures passed")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString("stream", failure.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
