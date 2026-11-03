package com.bigmoe.onedge.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ModelProfileIdentityTest {
    @Test fun sameFileHasStableIdentityAndDifferentPathsDoNotCollideByName() {
        val root = Files.createTempDirectory("model-profile-identity").toFile()
        try {
            val first = File(root, "a/same.gguf").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            val second = File(root, "b/same.gguf").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            val a = ModelProfileIdentity.fromPath(first.absolutePath)
            assertNotNull(a)
            assertEquals(a, ModelProfileIdentity.fromPath(first.absolutePath))
            assertNotEquals(a, ModelProfileIdentity.fromPath(second.absolutePath))
            first.writeBytes(byteArrayOf(1, 2, 3, 4))
            first.setLastModified(first.lastModified() + 2000)
            assertNotEquals(a, ModelProfileIdentity.fromPath(first.absolutePath))
        } finally {
            root.deleteRecursively()
        }
    }
}
