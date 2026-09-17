package com.subtitleedit

import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.os.Bundle

/** Routes system "Open with" requests to a fresh editor document. */
class OpenSubtitleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (intent.action == Intent.ACTION_VIEW && uri != null &&
            (uri.scheme == ContentResolver.SCHEME_CONTENT || uri.scheme == ContentResolver.SCHEME_FILE)
        ) {
            // Forward only the document and its grants. Internal file/media extras are not
            // part of the public opening contract, and existing unsaved editors stay intact.
            startActivity(Intent(this, EditorActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, intent.type)
                clipData = ClipData.newRawUri("subtitle", uri)
                flags = intent.flags and (
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    )
            })
        }
        finish()
    }
}
