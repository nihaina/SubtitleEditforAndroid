package com.subtitleedit

/** One native artwork entry; pictureType uses TagLib's standard picture type names. */
internal data class MediaTagPicture(
    val data: ByteArray,
    val mimeType: String,
    val description: String,
    val pictureType: String
)

/** Audio tags are read and written by the same tag-aware library, without remuxing audio. */
internal object MediaTagNative {
    init {
        System.loadLibrary("subtitleedit_tags")
    }

    /** Alternating uppercase property key and value; null means an unsupported file. */
    external fun read(fd: Int): Array<String>?
    external fun pictures(fd: Int): Array<MediaTagPicture>
    external fun supportsCover(fd: Int): Boolean

    /** Descriptors are borrowed. coverMode: 0 preserve, 1 delete all, 2 replace front cover. */
    external fun write(
        fd: Int,
        keys: Array<String>,
        values: Array<String>,
        coverMode: Int,
        coverData: ByteArray?,
        coverMime: String
    ): Boolean
}
