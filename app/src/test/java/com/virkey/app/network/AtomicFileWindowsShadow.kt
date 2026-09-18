package com.virkey.app.network

import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter

/**
 * Android's rename replaces an existing destination atomically. Robolectric calls
 * File.renameTo on the host JVM, which cannot replace files on Windows. Emulate
 * Android's rename semantics with the host's real atomic-move API; all other
 * AtomicFile behavior, disk IO, encryption and authentication remain exercised.
 */
@Implements(AtomicFile::class)
class AtomicFileWindowsShadow {
    companion object {
        var failNextRename = false

        @JvmStatic @Implementation(minSdk = 29)
        fun rename(source: File, target: File) {
            if (failNextRename) { failNextRename = false; return }
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        @JvmStatic @Resetter fun reset() { failNextRename = false }
    }
}
