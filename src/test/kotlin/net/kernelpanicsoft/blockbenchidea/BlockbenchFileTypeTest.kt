package net.kernelpanicsoft.blockbenchidea

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import net.kernelpanicsoft.blockbenchidea.fileTypes.BlockbenchFileType

class BlockbenchFileTypeTest : BasePlatformTestCase() {

    fun testBbmodelExtensionRecognized() {
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension("bbmodel")
        assertEquals(BlockbenchFileType, fileType)
    }

    fun testVirtualFilesAreRecognized() {
        val model = myFixture.addFileToProject("test.bbmodel", DEFAULT_MODEL)
        assertEquals(BlockbenchFileType, model.fileType)

        val geometry = myFixture.addFileToProject("cube.geo.json", DEFAULT_MODEL)
        assertTrue(geometry.fileType != BlockbenchFileType)
    }

    companion object {
        private const val DEFAULT_MODEL =
            """{"format_version":"1.16.0","model_identifier":"test","bones":[]}"""
    }
}