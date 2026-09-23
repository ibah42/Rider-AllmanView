package com.allmanview.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The path half of "is this somebody else's code", tested without an editor. */
class ForeignSourcePolicyTest {

    @Test
    fun `a package under Library PackageCache is foreign`() {
        assertTrue(
            ForeignSourcePolicy.isForeignPath(
                "C:/Game/Library/PackageCache/com.cysharp.unitask@2.5.10/Runtime/UniTask.cs",
            ),
        )
    }

    @Test
    fun `backslashes and any letter case are read the same way`() {
        assertTrue(
            ForeignSourcePolicy.isForeignPath(
                "C:\\Game\\library\\PACKAGECACHE\\com.unity.mathematics@1.3.2\\float3.cs",
            ),
        )
    }

    @Test
    fun `the project's own sources are not foreign`() {
        assertFalse(ForeignSourcePolicy.isForeignPath("C:/Game/Assets/Scripts/Player.cs"))
    }

    @Test
    fun `an embedded package under Packages is the project's own`() {
        // Embedded packages are edited in place and committed; only the unpacked cache is not.
        assertFalse(ForeignSourcePolicy.isForeignPath("C:/Game/Packages/com.studio.core/Runtime/Core.cs"))
    }

    @Test
    fun `a folder merely named like the cache is not the cache`() {
        assertFalse(ForeignSourcePolicy.isForeignPath("C:/Game/Assets/PackageCacheTools/Cleaner.cs"))
        assertFalse(ForeignSourcePolicy.isForeignPath("C:/Game/Assets/Library/Books.cs"))
    }

    @Test
    fun `a source Rider decompiled or downloaded is foreign`() {
        assertTrue(
            ForeignSourcePolicy.isForeignPath(
                "C:/Users/xxxx/AppData/Roaming/JetBrains/Rider2026.2/resharper-host/SourcesCache/" +
                    "d44b17e136d09081da1e8fd0e8c83fde60e11818b9f2442d7ed8cb733fae895f/System.Collections.Generic.cs",
            ),
        )
    }

    @Test
    fun `a project folder merely called SourcesCache is the project's own`() {
        assertFalse(ForeignSourcePolicy.isForeignPath("C:/Game/Assets/SourcesCache/Loader.cs"))
    }
}
