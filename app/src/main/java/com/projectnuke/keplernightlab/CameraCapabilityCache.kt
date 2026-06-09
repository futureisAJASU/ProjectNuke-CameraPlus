package com.projectnuke.keplernightlab

import android.content.Context

object CameraCapabilityCache {
    private val lock = Any()
    private var backCameraCandidates: List<CameraCandidate>? = null
    private val resolutionCapabilities = mutableMapOf<String, CameraResolutionCapability>()

    fun getBackCameraCandidates(context: Context): List<CameraCandidate> {
        synchronized(lock) {
            backCameraCandidates?.let { return it }
        }
        val loaded = loadBackCameraCandidates(context.applicationContext)
        synchronized(lock) {
            backCameraCandidates = loaded
            return loaded
        }
    }

    fun getResolutionCapability(
        context: Context,
        cameraId: String,
        lensSlot: LensSlot
    ): CameraResolutionCapability {
        val key = "$cameraId:${lensSlot.name}"
        synchronized(lock) {
            resolutionCapabilities[key]?.let { return it }
        }
        val loaded = loadCameraResolutionCapability(context.applicationContext, cameraId, lensSlot)
        synchronized(lock) {
            resolutionCapabilities[key] = loaded
            return loaded
        }
    }
}
