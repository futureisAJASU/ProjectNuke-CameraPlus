package com.projectnuke.keplernightlab

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val KEPLER_JOB_SCHEMA_VERSION = 1

/** Serializes each job's read-modify-write updates and never truncates a valid job.json. */
object KeplerJobMetadata {
    private val locks = ConcurrentHashMap<String, Any>()
    private val operationLocks = ConcurrentHashMap<String, AtomicBoolean>()

    private fun lockFor(jobDir: File): Any = locks.getOrPut(jobDir.canonicalPath) { Any() }

    fun tryAcquireOperation(jobDir: File): Boolean =
        operationLocks.getOrPut(jobDir.canonicalPath) { AtomicBoolean(false) }.compareAndSet(false, true)

    fun releaseOperation(jobDir: File) {
        operationLocks[jobDir.canonicalPath]?.set(false)
    }

    fun isOperationActive(jobDir: File): Boolean = operationLocks[jobDir.canonicalPath]?.get() == true

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
