package com.loom.studio.pipeline

import com.loom.studio.chiplets.Profile
import com.loom.studio.core.Level
import com.loom.studio.importer.ProjectSpec
import java.io.File

/** Inference profiles of a loom.json project: model VARIANTS are swapped like any other profile. */
object ProjectProfiles {
    fun inference(spec: ProjectSpec, dir: File): List<Profile>? {
        val inf = spec.models.filter { it.role == "inference" }
        if (inf.size < 2) return null
        val ordered = inf.sortedBy { it.level?.ordinal ?: 9 }
        val profiles = ordered.take(3).mapIndexed { i, m ->
            val delegate = if (m.file.endsWith(".onnx")) "cpu" else "gpu"
            Profile(Level.entries[i], "${m.id} ${delegate.uppercase()}", mapOf("file" to File(dir, m.file).absolutePath, "delegate" to delegate, "threads" to 4))
        }.toMutableList()
        if (profiles.size < 3) {
            val small = ordered.last()
            profiles += Profile(Level.entries[profiles.size], "${small.id} CPU ×2", mapOf("file" to File(dir, small.file).absolutePath, "delegate" to "cpu", "threads" to 2))
        }
        return profiles
    }
}
