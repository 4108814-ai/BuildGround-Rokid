package com.anezium.rokidbus.plugin.assistant

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal interface AssistantAtomicFileOperations {
    fun atomicReplace(source: File, target: File)
    fun replace(source: File, target: File)
}

internal object NioAssistantAtomicFileOperations : AssistantAtomicFileOperations {
    override fun atomicReplace(source: File, target: File) {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    override fun replace(source: File, target: File) {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

internal fun writeAssistantJsonAtomically(
    target: File,
    text: String,
    fileOperations: AssistantAtomicFileOperations = NioAssistantAtomicFileOperations,
) {
    val parent = checkNotNull(target.parentFile)
    if (!parent.isDirectory && !parent.mkdirs()) {
        error("Assistant store directory could not be created.")
    }
    val temporary = File(parent, ".${target.name}.tmp")
    try {
        FileOutputStream(temporary).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        try {
            fileOperations.atomicReplace(temporary, target)
        } catch (_: AtomicMoveNotSupportedException) {
            fileOperations.replace(temporary, target)
        }
    } finally {
        if (temporary.exists()) temporary.delete()
    }
}
