package net.kernelpanicsoft.blockbenchidea.fileTypes

import com.intellij.openapi.fileTypes.FileTypeConsumer
import com.intellij.openapi.fileTypes.FileTypeFactory

/** Associates [BlockbenchFileType] with `.bbmodel` files. */
@Suppress("DEPRECATION")
class BlockbenchFileTypeFactory : FileTypeFactory() {

    override fun createFileTypes(consumer: FileTypeConsumer) {
        consumer.consume(BlockbenchFileType, "bbmodel")
    }
}