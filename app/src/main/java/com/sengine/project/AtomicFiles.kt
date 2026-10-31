package com.sengine.project

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Replace documents only after a complete, flushed write. Existing valid files survive failures. */
object AtomicFiles {
    fun writeText(file: File, text: String) = writeBytes(file, text.toByteArray(Charsets.UTF_8))
    fun writeBytes(file: File, bytes: ByteArray) {
        val parent = file.canonicalFile.parentFile
        parent.mkdirs()
        val temp = File.createTempFile(".sengine-", ".tmp", parent)
        try {
            FileOutputStream(temp).use { out -> out.write(bytes); out.fd.sync() }
            // POSIX rename replaces the directory entry in one operation. Never delete the
            // previous valid document first: a failed replacement must leave it readable.
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
}
