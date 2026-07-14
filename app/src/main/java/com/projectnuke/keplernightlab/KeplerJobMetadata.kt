package com.projectnuke.keplernightlab

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

private const val KEPLER_JOB_SCHEMA_VERSION = 1

/** Serializes each job's read-modify-write updates and never truncates a valid job.json. */
object KeplerJobMetadata {
    private val locks = ConcurrentHashMap<String, Any>()
    private val operationLeases = ConcurrentHashMap<String, JobOperationLease>()

    private fun lockFor(jobDir: File): Any = locks.getOrPut(jobDir.canonicalPath) { Any() }

    fun acquireOperation(jobDir: File): JobOperationLease? {
        val key = jobDir.canonicalPath
        val lease = JobOperationLease(key)
        return if (operationLeases.putIfAbsent(key, lease) == null) lease else null
    }

    fun isOperationActive(jobDir: File): Boolean = operationLeases.containsKey(jobDir.canonicalPath)

    internal fun releaseOperation(lease: JobOperationLease) {
        operationLeases.remove(lease.key, lease)
    }

    fun read(jobDir: File): JSONObject = synchronized(lockFor(jobDir)) {
        JSONObject(File(jobDir, JOB_JSON_FILE_NAME).readText())
    }

    fun write(jobDir: File, job: JSONObject): JSONObject = synchronized(lockFor(jobDir)) {
        val replacement = JSONObject(job.toString())
        replacement.put("schemaVersion", replacement.optInt("schemaVersion", KEPLER_JOB_SCHEMA_VERSION))
        atomicWrite(File(jobDir, JOB_JSON_FILE_NAME), replacement.toString(2))
        replacement
    }

    fun update(jobDir: File, mutate: (JSONObject) -> Unit): JSONObject = synchronized(lockFor(jobDir)) {
        val job = JSONObject(File(jobDir, JOB_JSON_FILE_NAME).readText())
        mutate(job)
        job.put("schemaVersion", job.optInt("schemaVersion", KEPLER_JOB_SCHEMA_VERSION))
        atomicWrite(File(jobDir, JOB_JSON_FILE_NAME), job.toString(2))
        job
    }

    fun atomicWrite(file: File, text: String) {
        val parent = file.parentFile ?: error("job metadata parent missing")
        check(parent.exists() || parent.mkdirs()) { "Could not create ${parent.absolutePath}" }
        val temp = File(parent, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            atomicReplace(temp, file)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun atomicReplace(temp: File, destination: File) {
        try {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

class JobOperationLease internal constructor(internal val key: String) {
    @Volatile private var released = false

    fun release() {
        if (!released) {
            released = true
            KeplerJobMetadata.releaseOperation(this)
        }
    }
}
