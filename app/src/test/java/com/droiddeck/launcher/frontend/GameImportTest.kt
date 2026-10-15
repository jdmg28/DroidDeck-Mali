package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameImportTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(path: File, entries: Map<String, String>) {
        ZipOutputStream(path.outputStream()).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) out.write(content.toByteArray())
                out.closeEntry()
            }
        }
    }

    @Test fun aZipBecomesAFolderInTheGamesDirWithItsOwnName() {
        val games = tmp.newFolder("PC")
        SessionPrefs.setAddedGamesDirs(app, listOf(games.path))
        val archive = tmp.newFile("Brawl Quest.zip")
        zip(archive, mapOf(
            "Brawl Quest.exe" to "game",
            "data/gfx.pak" to "art",
            "__MACOSX/._Brawl Quest.exe" to "junk",
            "data/._metadata" to "junk",
        ))
        val folder = GameImport.importZip(app, archive)
        assertEquals(File(games, "Brawl Quest"), folder)
        assertEquals("game", File(folder, "Brawl Quest.exe").readText())
        assertEquals("art", File(folder, "data/gfx.pak").readText())
        assertFalse(File(folder, "__MACOSX").exists())
        assertFalse(File(folder, "data/._metadata").exists())
    }

    @Test fun anEntryLeavingTheFolderIsRejectedAndTheFolderRemoved() {
        val games = tmp.newFolder("PC")
        SessionPrefs.setAddedGamesDirs(app, listOf(games.path))
        val archive = tmp.newFile("Evil.zip")
        zip(archive, mapOf("Game/ok.exe" to "fine", "../escaped.exe" to "bad"))
        try {
            GameImport.importZip(app, archive)
            fail("expected ImportException")
        } catch (expected: GameImport.ImportException) {
            assertTrue(expected.message!!.contains("escapes"))
        }
        assertTrue((games.list() ?: arrayOf()).isEmpty())
    }

    @Test fun aSecondImportOfTheSameZipLandsBesideTheFirst() {
        val games = tmp.newFolder("PC")
        SessionPrefs.setAddedGamesDirs(app, listOf(games.path))
        val archive = tmp.newFile("Racer.zip")
        zip(archive, mapOf("Racer.exe" to "v1"))
        assertEquals("v1", File(GameImport.importZip(app, archive), "Racer.exe").readText())
        zip(archive, mapOf("Racer.exe" to "v2"))
        val second = GameImport.importZip(app, archive)
        assertEquals(File(games, "Racer-2"), second)
        assertEquals("v2", File(second, "Racer.exe").readText())
    }

    @Test fun withNoGamesFolderChosenOneIsCreatedAndChosen() {
        SessionPrefs.setAddedGamesDirs(app, emptyList())
        val base = GameImport.targetDir(app)
        assertTrue(base.isDirectory)
        assertEquals(listOf(base.path), SessionPrefs.addedGamesDirs(app))
    }

    @Test fun anEpicAppImageIsStagedExecutableAtTheEmulatorsPlace() {
        val appimage = tmp.newFile("EpicGamesLauncher-x86_64.AppImage")
        appimage.writeText("launcher")
        val staged = GameImport.stageAppImage(app, appimage)
        assertEquals("EpicGamesLauncher.AppImage", staged.name)
        assertTrue(staged.canExecute())
        assertTrue(staged.path.contains("opt/appimages"))
        assertEquals(staged, GameImport.epicProgram(app))
        appimage.writeText("update")
        GameImport.stageAppImage(app, appimage)
        assertEquals("update", GameImport.epicProgram(app)!!.readText())
    }
}
