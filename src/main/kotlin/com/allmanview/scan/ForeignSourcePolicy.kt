package com.allmanview.scan

/**
 * Whether a file's path says it belongs to somebody else's code, so the formatting markers --
 * edge whitespace and member spacing -- have nothing to ask of it.
 *
 * Only the path half of that question lives here. Whether the editor, the document or the file
 * is writable, and whether the file is on the local disk at all, are answered by the platform in
 * `AllmanController.isForeignSource`; this is the part that needs no `Editor` and can be tested.
 */
object ForeignSourcePolicy {

    /**
     * Where Unity unpacks packages pulled in through the manifest -- a registry, a git URL,
     * OpenUPM. Unity overwrites the folder on every resolve, so any fix made there is lost, and
     * Rider does not mark these files read-only: the path is the only thing that tells them apart
     * from the project's own sources.
     */
    private const val UNITY_PACKAGE_CACHE_SEGMENT = "/library/packagecache/"

    /**
     * Where Rider writes the sources it shows for a compiled assembly -- decompiled, or fetched
     * through SourceLink or a symbol server: `%APPDATA%/JetBrains/Rider<version>/resharper-host/
     * SourcesCache/<hash>/...`. Those files arrive writable, on the local disk, and look like any
     * other source to the platform; the folder is the only thing that gives them away. Seen on
     * Rider 2026.2 with `System.Collections.Generic.cs`.
     */
    private const val RIDER_SOURCES_CACHE_SEGMENT = "/resharper-host/sourcescache/"

    private val FOREIGN_SEGMENTS = listOf(UNITY_PACKAGE_CACHE_SEGMENT, RIDER_SOURCES_CACHE_SEGMENT)

    /**
     * @param path a file path in either separator style: IntelliJ hands out "/" everywhere, a
     *   Windows path pasted from elsewhere uses "\".
     */
    fun isForeignPath(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase()
        for (segment in FOREIGN_SEGMENTS) {
            if (normalized.contains(segment)) {
                return true
            }
        }
        return false
    }
}
