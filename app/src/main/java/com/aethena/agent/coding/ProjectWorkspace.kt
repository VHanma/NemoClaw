package com.aethena.agent.coding

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectWorkspace(private val context: Context) {
    fun saveProject(name: String, modelOutput: String): File {
        val safeName = name
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .take(48)
            .ifBlank { "aethena-project" }

        val projectsRoot = File(context.filesDir, "projects").apply { mkdirs() }
        val workDir = File(projectsRoot, "$safeName-${System.currentTimeMillis()}").apply { mkdirs() }
        val blockRegex = Regex(
            pattern = "===FILE:(.+?)===\\s*\\n(.*?)\\n===END FILE===",
            options = setOf(RegexOption.DOT_MATCHES_ALL)
        )
        var written = 0

        blockRegex.findAll(modelOutput).forEach { match ->
            val relative = match.groupValues[1]
                .trim()
                .replace('\\', '/')
                .trimStart('/')
            if (relative.isBlank() || relative.split('/').any { it == ".." }) return@forEach

            val target = File(workDir, relative)
            val rootPath = workDir.canonicalFile.toPath()
            val targetPath = target.canonicalFile.toPath()
            if (!targetPath.startsWith(rootPath)) return@forEach

            target.parentFile?.mkdirs()
            target.writeText(match.groupValues[2])
            written++
        }

        if (written == 0) {
            File(workDir, "AETHENA_RESPONSE.txt").writeText(modelOutput)
        }

        val zip = File(projectsRoot, "$safeName.zip")
        zipDirectory(workDir, zip)
        return zip
    }

    private fun zipDirectory(source: File, destination: File) {
        ZipOutputStream(FileOutputStream(destination)).use { zip ->
            source.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relative = file.relativeTo(source).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(relative))
                    FileInputStream(file).use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }
}
