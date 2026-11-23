package cz.starenka.jiffy.vfs

import org.apache.sshd.sftp.server.DefaultGroupPrincipal
import org.apache.sshd.sftp.server.DefaultUserPrincipal
import java.nio.file.AccessDeniedException
import java.nio.file.attribute.*
import java.util.Collections

internal object RootAttributes : PosixFileAttributes {
    private val epoch = FileTime.fromMillis(0)
    private val owner = DefaultUserPrincipal("jiffy")
    private val group = DefaultGroupPrincipal("jiffy")
    private val mode = Collections.unmodifiableSet(PosixFilePermissions.fromString("r-xr-xr-x"))

    override fun isDirectory() = true
    override fun isRegularFile() = false
    override fun isSymbolicLink() = false
    override fun isOther() = false
    override fun size() = 0L
    override fun fileKey() = "/"
    override fun lastModifiedTime() = epoch
    override fun lastAccessTime() = epoch
    override fun creationTime() = epoch
    override fun owner() = owner
    override fun group() = group
    override fun permissions(): Set<PosixFilePermission> = mode

    private val basic = mapOf(
        "isDirectory" to isDirectory(), "isRegularFile" to isRegularFile(),
        "isSymbolicLink" to isSymbolicLink(), "isOther" to isOther(),
        "size" to size(), "fileKey" to fileKey(), "lastModifiedTime" to lastModifiedTime(),
        "lastAccessTime" to lastAccessTime(), "creationTime" to creationTime(),
    )
    private val posix = basic + mapOf("permissions" to mode, "owner" to owner, "group" to group)

    fun read(attributes: String): Map<String, Any> {
        val values = when (attributes.substringBefore(':', "basic")) {
            "basic" -> basic
            "owner" -> mapOf("owner" to owner)
            "posix" -> posix
            else -> throw UnsupportedOperationException(attributes)
        }
        val names = attributes.substringAfter(':').split(',')
        if ("*" in names) return values.toMap()
        return names.associateWith { values[it] ?: throw IllegalArgumentException("Unknown attribute: $it") }
    }

    fun <V : FileAttributeView?> view(type: Class<V>): V? {
        val name = when (type) {
            BasicFileAttributeView::class.java -> "basic"
            PosixFileAttributeView::class.java -> "posix"
            FileOwnerAttributeView::class.java -> "owner"
            else -> return null
        }
        return type.cast(object : PosixFileAttributeView {
            override fun name() = name
            override fun readAttributes() = RootAttributes
            override fun getOwner() = RootAttributes.owner()
            override fun setPermissions(perms: Set<PosixFilePermission>?) = throw AccessDeniedException("/")
            override fun setOwner(value: UserPrincipal?) = throw AccessDeniedException("/")
            override fun setGroup(value: GroupPrincipal?) = throw AccessDeniedException("/")
            override fun setTimes(modified: FileTime?, access: FileTime?, creation: FileTime?) = throw AccessDeniedException("/")
        })
    }
}
