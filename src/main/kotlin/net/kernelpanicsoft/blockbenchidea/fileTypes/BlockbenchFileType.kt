package net.kernelpanicsoft.blockbenchidea.fileTypes

import net.kernelpanicsoft.blockbenchidea.BlockbenchBundle
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * File type for Blockbench model files.
 *
 * Matches `.bbmodel` projects opened in the embedded Blockbench editor.
 */
class BlockbenchFileType : FileType {

    private val iconCache: Icon? by lazy {
        IconLoader.getIcon("/icons/blockbench.svg", BlockbenchFileType::class.java)
    }

    override fun getName(): String = "Blockbench Model"

    override fun getDisplayName(): String = BlockbenchBundle["editor.fileTypeName"]

    override fun getDescription(): String = BlockbenchBundle["editor.fileTypeDescription"]

    override fun getDefaultExtension(): String = "bbmodel"

    override fun getIcon(): Icon? = iconCache

    override fun isBinary(): Boolean = false

    override fun getCharset(file: VirtualFile, content: ByteArray): String = "UTF-8"

}