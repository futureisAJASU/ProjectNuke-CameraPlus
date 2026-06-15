package com.projectnuke.keplernightlab

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

object CameraCapabilityCache {
    private val lock = Any()
    private var backCameraCandidates: List<CameraCandidate>? = null
    private val resolutionCapabilities = mutableMapOf<String, CameraResolutionCapability>()

    fun clear() {
        synchronized(lock) {
            backCameraCandidates = null
            resolutionCapabilities.clear()
        }
    }

    fun getBackCameraCandidates(context: Context): List<CameraCandidate> {
        synchronized(lock) {
            backCameraCandidates?.let { cached ->
                if (hasCompletePhysicalMetadata(context, cached)) return cached
                backCameraCandidates = null
            }
        }
        val loaded = loadBackCameraCandidates(context.applicationContext)
        synchronized(lock) {
            backCameraCandidates = loaded
            return loaded
        }
    }

    private fun hasCompletePhysicalMetadata(
        context: Context,
        candidates: List<CameraCandidate>
    ): Boolean {
        if (Build.VERSION.SDK_INT < 28) return true
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return candidates.all { candidate ->
            val expectedIds = runCatching {
                manager.getCameraCharacteristics(candidate.cameraId).physicalCameraIds
            }.getOrDefault(emptySet())
            expectedIds == candidate.physicalCameras.map { it.physicalCameraId }.toSet()
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
