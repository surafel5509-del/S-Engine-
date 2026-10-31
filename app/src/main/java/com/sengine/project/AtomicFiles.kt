package com.sengine.project

import java.io.File
import java.io.FileOutputStream

/** Replace documents only after a complete, flushed write. Existing valid files survive failures. */
object AtomicFiles {
    fun writeText(file: File, text: String) = writeBytes(file, text.toByteArray(Charsets.UTF_8))
    fun writeBytes(file: File, bytes: ByteArray) {
        val parent = file.canonicalFile.parentFile
        parent.mkdirs()
        val temp = File.createTempFile(".sengine-", ".tmp", parent)
        try {
            FileOutputStream(temp).use { out -> out.write(bytes); out.fd.sync() }
            if (file.exists() && !file.delete()) {
                if (!temp.renameTo(file)) throw java.io.IOException("Could not replace ${file.name}")
            } else if (!temp.renameTo(file)) throw java.io.IOException("Could not save ${file.name}")
        } finally { temp.delete() }
    }
}
