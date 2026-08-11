package com.projectnuke.keplernightlab

/** Pure generation fence used by preview tests and mirrored by CameraPreviewController. */
internal class PreviewGenerationOwner {
    enum class State { STOPPED, STARTING, OPEN, STOPPING, FAILED }

    data class Snapshot(
        val generation: Long = 0L,
        val desiredRunning: Boolean = false,
        val state: State = State.STOPPED
    )

    private var nextGeneration = 0L
    private var snapshot = Snapshot()

    @Synchronized
    fun snapshot(): Snapshot = snapshot

    @Synchronized
    fun start(): Long? {
        snapshot = snapshot.copy(desiredRunning = true)
        if (snapshot.state == State.STARTING || snapshot.state == State.OPEN) return null
        if (snapshot.state == State.STOPPING) return null
        val generation = ++nextGeneration
        snapshot = Snapshot(generation, desiredRunning = true, state = State.STARTING)
        return generation
    }

    @Synchronized
    fun markOpen(generation: Long): Boolean {
        if (snapshot.generation != generation || snapshot.state != State.STARTING) return false
        snapshot = snapshot.copy(state = State.OPEN)
        return true
    }

    @Synchronized
    fun stop(): Long? {
        snapshot = snapshot.copy(desiredRunning = false)
        if (snapshot.state == State.STOPPED || snapshot.state == State.STOPPING) return null
        snapshot = snapshot.copy(generation = ++nextGeneration, state = State.STOPPING)
        return snapshot.generation
    }

    @Synchronized
    fun finishStop(): Boolean {
        if (snapshot.state != State.STOPPING) return false
        snapshot = snapshot.copy(state = State.STOPPED)
        return snapshot.desiredRunning
    }

    @Synchronized
    fun fail(generation: Long): Boolean {
        if (snapshot.generation != generation) return false
        snapshot = snapshot.copy(state = State.FAILED)
        return true
    }

    @Synchronized
    fun accepts(generation: Long): Boolean = snapshot.generation == generation &&
        snapshot.state != State.STOPPED && snapshot.state != State.FAILED
}
