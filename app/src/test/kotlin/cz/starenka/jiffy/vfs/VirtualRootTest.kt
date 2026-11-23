package cz.starenka.jiffy.vfs

import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.attribute.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VirtualRootTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun rootHasReadOnlyMetadataAcrossAttributeApis() {
        val root = VFileSystem(VProvider, emptyMap()).root
        val view = Files.getFileAttributeView(root, PosixFileAttributeView::class.java)
        val attrs = view.readAttributes()
        assertTrue(attrs.isDirectory)
        assertTrue(Files.isReadable(root))
        assertFalse(Files.isWritable(root))
        assertEquals(PosixFilePermissions.fromString("r-xr-xr-x"), attrs.permissions())
        assertEquals(attrs.permissions(), Files.readAttributes(root, "posix:*")["permissions"])
        assertEquals(attrs.owner(), Files.getOwner(root))
        assertThrows(AccessDeniedException::class.java) { view.setPermissions(emptySet()) }
        assertThrows(AccessDeniedException::class.java) { view.setGroup(attrs.group()) }
        assertThrows(AccessDeniedException::class.java) { Files.setOwner(root, attrs.owner()) }
        assertThrows(AccessDeniedException::class.java) { Files.setLastModifiedTime(root, FileTime.fromMillis(1)) }
        assertThrows(AccessDeniedException::class.java) { Files.setAttribute(root, "posix:permissions", emptySet<PosixFilePermission>()) }
    }

    @Test fun rootListsOnlyConfiguredSharesAndSharedFilesRemainWritable() {
        val dir = temp.newFolder().toPath()
        val root = VFileSystem(VProvider, mapOf("Music" to dir)).root
        Files.newDirectoryStream(root).use { assertEquals(listOf("/Music"), it.map { path -> path.toString() }) }
        val track = root.resolve("Music/track")
        Files.write(track, byteArrayOf(42))
        assertArrayEquals(byteArrayOf(42), Files.readAllBytes(dir.resolve("track")))
    }

    @Test fun symlinksCannotEscapeShareEvenAfterDirectoryWasVerified() {
        val share = temp.newFolder().toPath()
        val outside = temp.newFolder().toPath()
        Files.write(outside.resolve("secret"), byteArrayOf(1))
        Files.createDirectory(share.resolve("sub"))
        Files.write(share.resolve("sub/ok"), byteArrayOf(2))
        val root = VFileSystem(VProvider, mapOf("Music" to share)).root
        assertArrayEquals(byteArrayOf(2), Files.readAllBytes(root.resolve("Music/sub/ok")))
        Files.createSymbolicLink(share.resolve("sub/link"), outside.resolve("secret"))
        assertThrows(AccessDeniedException::class.java) { Files.readAllBytes(root.resolve("Music/sub/link")) }
        Files.createSymbolicLink(share.resolve("dirlink"), outside)
        assertThrows(AccessDeniedException::class.java) { Files.readAllBytes(root.resolve("Music/dirlink/secret")) }
    }
}
