package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.EntryRepository
import io.github.edrock200.appdownloader.data.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RepositoryTest {

    private fun tempDir(): File = Files.createTempDirectory("repo").toFile()
    private fun entry(name: String) = AppEntry(name = name, type = SourceType.URL, source = "https://x/$name.apk")

    @Test
    fun savesAtomicallyAndKeepsABackupCopy() = runBlocking {
        val dir = tempDir()
        val repo = EntryRepository(File(dir, "entries.json"))
        repo.add(entry("a"))
        repo.add(entry("b"))
        assertTrue(File(dir, "entries.json.bak").exists())
        assertEquals(listOf("a", "b"), EntryRepository(File(dir, "entries.json")).entries.value.map { it.name })
    }

    @Test
    fun damagedFileFallsBackToBackupAndIsKept() = runBlocking {
        val dir = tempDir()
        val file = File(dir, "entries.json")
        EntryRepository(file).apply { add(entry("a")); add(entry("b")) } // .bak now holds [a]
        file.writeText("{ this is not json")
        val repo = EntryRepository(file)
        assertEquals(listOf("a"), repo.entries.value.map { it.name })
        assertNotNull(repo.problem.value)
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("entries.corrupt-") })
    }

    @Test
    fun oneUnreadableEntryDoesNotLoseTheRest() {
        val dir = tempDir()
        val file = File(dir, "entries.json")
        file.writeText(
            """[{"id":"1","name":"Good","type":"URL","source":"https://x/a.apk"},
                {"id":"2","name":"Bad","type":"SOMETHING_NEW","source":"x"}]"""
        )
        val repo = EntryRepository(file)
        assertEquals(listOf("Good"), repo.entries.value.map { it.name })
        assertTrue(repo.problem.value!!.contains("couldn't be read"))
    }

    @Test
    fun unknownValuesInOptionalFieldsAreIgnored() {
        val dir = tempDir()
        val file = File(dir, "entries.json")
        file.writeText("""[{"id":"1","name":"A","type":"URL","source":"https://x/a.apk","remoteChange":"FROM_THE_FUTURE"}]""")
        assertEquals(1, EntryRepository(file).entries.value.size)
    }

    @Test
    fun failedSaveKeepsTheListAndReportsInsteadOfCrashing() = runBlocking {
        val dir = tempDir()
        val blocker = File(dir, "notadir").apply { writeText("x") }
        val repo = EntryRepository(File(blocker, "entries.json")) // Parent is a file: every write fails.
        repo.add(entry("a"))
        assertEquals(listOf("a"), repo.entries.value.map { it.name })
        assertEquals(EntryRepository.SAVE_FAILED, repo.problem.value)
    }
}
