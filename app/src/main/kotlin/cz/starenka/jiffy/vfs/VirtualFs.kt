package cz.starenka.jiffy.vfs

import java.io.IOException
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.*
import java.nio.file.spi.FileSystemProvider

/**
 * Virtual filesystem: "/" lists the enabled shares; "/<share>/..." maps onto a real directory.
 * Nothing outside a share's real directory is ever reachable (lexical normalisation + real-path
 * containment check, so symlinks can't escape either). The virtual root is read-only.
 */
class VFileSystem(
    private val provider: VProvider,
    private val shares: Map<String, Path>,
) : FileSystem() {
    val root = VPath(this, emptyList(), true)
    private val realBases = HashMap<String, Path>()
    private val verifiedDirs: MutableSet<Path> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun shareNames(): List<String> = shares.keys.sorted()

    fun real(p: Path): Path {
        val v = p as? VPath ?: throw ProviderMismatchException()
        val abs = v.toAbsolutePath() as VPath
        if (abs.parts.isEmpty()) throw AccessDeniedException("/", null, "Virtual root has no backing path")
        val name = abs.parts[0]
        val base = shares[name] ?: throw NoSuchFileException(abs.toString())
        val realBase = synchronized(realBases) {
            realBases.getOrPut(name) { base.toRealPath() }
        }
        var r = base
        for (n in abs.parts.drop(1)) r = r.resolve(n)
        if (abs.parts.size == 1) return r
        // Fast path for directory listings: once a parent directory has passed the full check,
        // an entry in it only needs to not be a symlink (one lstat instead of a realpath walk).
        val parent = r.parent
        if (parent != null && parent in verifiedDirs && !Files.isSymbolicLink(r)) return r
        // containment check on the deepest existing ancestor (handles symlinks)
        var probe: Path = r
        while (!Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) probe = probe.parent ?: break
        if (!probe.toRealPath().startsWith(realBase)) throw AccessDeniedException(abs.toString())
        if (parent != null && Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) && parent.toRealPath().startsWith(realBase))
            verifiedDirs.add(parent)
        return r
    }

    override fun provider(): FileSystemProvider = provider
    override fun close() {}
    override fun isOpen() = true
    override fun isReadOnly() = false
    override fun getSeparator() = "/"
    override fun getRootDirectories(): Iterable<Path> = listOf(root)
    override fun getFileStores(): Iterable<FileStore> = emptyList()
    override fun supportedFileAttributeViews(): Set<String> = setOf("basic", "posix")
    override fun getPath(first: String, vararg more: String): Path =
        VPath.parse(this, (listOf(first) + more).filter { it.isNotEmpty() }.joinToString("/"))
    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher =
        FileSystems.getDefault().getPathMatcher(syntaxAndPattern)
    override fun getUserPrincipalLookupService(): UserPrincipalLookupService =
        FileSystems.getDefault().userPrincipalLookupService
    override fun newWatchService(): WatchService = throw UnsupportedOperationException()
}

class VPath(val vfs: VFileSystem, val parts: List<String>, val absolute: Boolean) : Path {
    companion object {
        fun parse(vfs: VFileSystem, s: String): VPath {
            val abs = s.startsWith("/")
            val st = ArrayList<String>()
            for (seg in s.split('/')) when (seg) {
                "", "." -> {}
                ".." -> if (st.isNotEmpty() && st.last() != "..") st.removeAt(st.size - 1) else if (!abs) st.add("..")
                else -> st.add(seg)
            }
            return VPath(vfs, st, abs)
        }
    }

    private fun of(p: List<String>, abs: Boolean = absolute) = VPath(vfs, p, abs)
    private fun v(o: Path) = o as? VPath ?: throw ProviderMismatchException()

    override fun getFileSystem() = vfs
    override fun isAbsolute() = absolute
    override fun getRoot(): Path? = if (absolute) vfs.root else null
    override fun getFileName(): Path? = if (parts.isEmpty()) null else of(listOf(parts.last()), false)
    override fun getParent(): Path? = when {
        parts.size > 1 -> of(parts.dropLast(1))
        parts.size == 1 && absolute -> vfs.root
        else -> null
    }
    override fun getNameCount() = parts.size
    override fun getName(index: Int): Path = of(listOf(parts[index]), false)
    override fun subpath(beginIndex: Int, endIndex: Int): Path = of(parts.subList(beginIndex, endIndex), false)
    override fun startsWith(other: Path): Boolean {
        val o = v(other)
        return o.absolute == absolute && o.parts.size <= parts.size && parts.subList(0, o.parts.size) == o.parts
    }
    override fun endsWith(other: Path): Boolean {
        val o = v(other)
        if (o.absolute) return o.absolute == absolute && o.parts == parts
        return o.parts.size <= parts.size && parts.subList(parts.size - o.parts.size, parts.size) == o.parts
    }
    override fun normalize(): Path = this
    override fun resolve(other: Path): Path {
        val o = v(other)
        if (o.absolute) return o
        return parse(vfs, (listOf(toString(), o.toString())).filter { it.isNotEmpty() }.joinToString("/"))
            .let { VPath(vfs, it.parts, absolute) }
    }
    override fun relativize(other: Path): Path {
        val o = v(other)
        require(o.absolute == absolute) { "'other' is different type of Path" }
        var i = 0
        while (i < parts.size && i < o.parts.size && parts[i] == o.parts[i]) i++
        return of(List(parts.size - i) { ".." } + o.parts.drop(i), false)
    }
    override fun startsWith(other: String) = startsWith(parse(vfs, other))
    override fun endsWith(other: String) = endsWith(parse(vfs, other))
    override fun resolve(other: String): Path = resolve(parse(vfs, other))
    override fun resolveSibling(other: Path): Path = parent?.resolve(other) ?: other
    override fun resolveSibling(other: String): Path = resolveSibling(parse(vfs, other))
    override fun toFile(): java.io.File = throw UnsupportedOperationException()
    override fun register(watcher: WatchService, vararg events: WatchEvent.Kind<*>): WatchKey = throw UnsupportedOperationException()
    override fun iterator(): MutableIterator<Path> = (0 until parts.size).map { getName(it) }.toMutableList().iterator()
    override fun toUri(): URI = URI("vfs", null, "/" + parts.joinToString("/"), null)
    override fun toAbsolutePath(): Path = if (absolute) this else of(parts, true)
    override fun toRealPath(vararg options: LinkOption): Path = toAbsolutePath()
    override fun register(watcher: WatchService, events: Array<out WatchEvent.Kind<*>>, vararg modifiers: WatchEvent.Modifier): WatchKey =
        throw UnsupportedOperationException()
    override fun compareTo(other: Path): Int = toString().compareTo(other.toString())
    override fun equals(other: Any?) = other is VPath && other.vfs === vfs && other.absolute == absolute && other.parts == parts
    override fun hashCode() = parts.hashCode() * 31 + absolute.hashCode()
    override fun toString() = (if (absolute) "/" else "") + parts.joinToString("/")
}

object VProvider : FileSystemProvider() {
    private fun fsOf(p: Path) = (p as? VPath ?: throw ProviderMismatchException()).vfs
    private fun r(p: Path) = fsOf(p).real(p)
    private fun prov(r: Path) = r.fileSystem.provider()
    private fun depth(p: Path) = (p.toAbsolutePath() as VPath).parts.size
    private fun denyIfShallow(p: Path) { if (depth(p) <= 1) throw AccessDeniedException(p.toString()) }

    override fun getScheme() = "vfs"
    override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem = throw UnsupportedOperationException()
    override fun getFileSystem(uri: URI): FileSystem = throw UnsupportedOperationException()
    override fun getPath(uri: URI): Path = throw UnsupportedOperationException()

    override fun newByteChannel(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel {
        if (depth(path) == 0) throw FileSystemException(path.toString(), null, "Is a directory")
        val x = r(path); return prov(x).newByteChannel(x, options, *attrs)
    }

    override fun newFileChannel(path: Path, options: Set<OpenOption>, vararg attrs: FileAttribute<*>): FileChannel {
        if (depth(path) == 0) throw FileSystemException(path.toString(), null, "Is a directory")
        val x = r(path); return prov(x).newFileChannel(x, options, *attrs)
    }

    override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> {
        val fs = fsOf(dir)
        val abs = dir.toAbsolutePath() as VPath
        if (abs.parts.isEmpty()) {
            val entries = fs.shareNames().map { VPath(fs, listOf(it), true) as Path }.filter { filter.accept(it) }
            return object : DirectoryStream<Path> {
                override fun iterator(): MutableIterator<Path> = entries.toMutableList().iterator()
                override fun close() {}
            }
        }
        val x = fs.real(abs)
        val inner = prov(x).newDirectoryStream(x) { true }
        return object : DirectoryStream<Path> {
            override fun iterator(): MutableIterator<Path> {
                val it = inner.iterator()
                return object : MutableIterator<Path> {
                    var nxt: Path? = null
                    private fun advance() {
                        while (nxt == null && it.hasNext()) {
                            val c = VPath(fs, abs.parts + it.next().fileName.toString(), true)
                            if (filter.accept(c)) nxt = c
                        }
                    }
                    override fun hasNext(): Boolean { advance(); return nxt != null }
                    override fun next(): Path { advance(); val n = nxt ?: throw NoSuchElementException(); nxt = null; return n }
                    override fun remove() = throw UnsupportedOperationException()
                }
            }
            override fun close() = inner.close()
        }
    }

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) {
        denyIfShallow(dir); val x = r(dir); prov(x).createDirectory(x, *attrs)
    }

    override fun delete(path: Path) { denyIfShallow(path); val x = r(path); prov(x).delete(x) }

    override fun copy(source: Path, target: Path, vararg options: CopyOption) {
        denyIfShallow(target); val s = r(source); val t = r(target); prov(s).copy(s, t, *options)
    }

    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        denyIfShallow(source); denyIfShallow(target); val s = r(source); val t = r(target); prov(s).move(s, t, *options)
    }

    override fun isSameFile(path: Path, path2: Path): Boolean {
        if (path == path2) return true
        if (depth(path) == 0 || depth(path2) == 0) return false
        val a = r(path); val b = r(path2); return prov(a).isSameFile(a, b)
    }

    override fun isHidden(path: Path): Boolean = path.fileName?.toString()?.startsWith(".") == true

    override fun getFileStore(path: Path): FileStore {
        if (depth(path) == 0) throw UnsupportedOperationException("Virtual root has no file store")
        val x = r(path); return prov(x).getFileStore(x)
    }

    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        if (depth(path) == 0) {
            if (AccessMode.WRITE in modes) throw AccessDeniedException(path.toString())
            return
        }
        val x = r(path); prov(x).checkAccess(x, *modes)
    }

    override fun <V : FileAttributeView?> getFileAttributeView(path: Path, type: Class<V>, vararg options: LinkOption): V? {
        if (depth(path) == 0) return RootAttributes.view(type)
        val x = r(path); return prov(x).getFileAttributeView(x, type, *options)
    }

    override fun <A : BasicFileAttributes> readAttributes(path: Path, type: Class<A>, vararg options: LinkOption): A {
        if (depth(path) == 0) {
            if (!type.isInstance(RootAttributes)) throw UnsupportedOperationException(type.name)
            return type.cast(RootAttributes)!!
        }
        val x = r(path); return prov(x).readAttributes(x, type, *options)
    }

    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): Map<String, Any> {
        if (depth(path) == 0) return RootAttributes.read(attributes)
        val x = r(path); return prov(x).readAttributes(x, attributes, *options)
    }

    override fun setAttribute(path: Path, attribute: String, value: Any?, vararg options: LinkOption) {
        if (depth(path) == 0) throw AccessDeniedException(path.toString())
        val x = r(path); prov(x).setAttribute(x, attribute, value, *options)
    }

    override fun createSymbolicLink(link: Path, target: Path, vararg attrs: FileAttribute<*>) {
        throw AccessDeniedException(link.toString(), null, "symlinks are not allowed")
    }

    override fun readSymbolicLink(link: Path): Path = throw NotLinkException(link.toString())
}
