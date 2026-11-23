package cz.starenka.jiffy

import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShareFolderTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun resolvedFolderMustBeConfirmedBeforeReplacingActiveShare() {
        var active = temp.newFolder().canonicalPath
        val original = active
        val next = temp.newFolder().canonicalPath
        val shown = Config.confirmDirectory("$next/.") { active = it }
        assertEquals(original, active)
        assertEquals(next, shown)
        Config.confirmDirectory(shown) { active = it }
        assertEquals(next, active)
    }

    @Test fun invalidFoldersNeverReplaceTheActiveShare() {
        val publish: (String) -> Unit = { fail("Invalid folder was published") }
        assertThrows(InvalidPathException::class.java) { Config.confirmDirectory("", publish) }
        assertThrows(NoSuchFileException::class.java) { Config.confirmDirectory("${temp.root}/missing", publish) }
        assertThrows(NotDirectoryException::class.java) { Config.confirmDirectory(temp.newFile().path, publish) }
    }
}
