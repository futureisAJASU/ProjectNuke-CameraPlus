package com.projectnuke.keplernightlab

import android.content.Context
import android.util.Log

/**
 * Process-scoped, stateless executor for [BackgroundProcessingRequest].
 * Uses [appContext] (applicationContext) only and reconstructs all
 * processing parameters from the exact job's durable metadata (job.json).
 * Must not capture Activity, Compose, or UI callbacks.
 */
internal object KeplerBackgroundExecutor : BackgroundProcessingExecutor {

    override fun execute(request: BackgroundProcessingRequest, appContext: Context) {
        val jobDir = request.exactJobDirectory
        val jobKind = request.jobKind
        val jobJson = try {
            KeplerJobMetadata.read(jobDir)
        } catch (e: Exception) {
            Log.e("KeplerBackgroundExecutor", "Failed to read job metadata for ${jobDir.name}", e)
            return
        }

        // Reconstruct all required params from durable job.json (not from closure)
        val captureMode = try {
            CaptureMode.valueOf(jobJson.optString("captureMode", CaptureMode.MULTI_FRAME.name))
        } catch (_: Exception) { CaptureMode.MULTI_FRAME }

        val finalOutputFormat = try {
            FinalOutputFormat.valueOf(jobJson.optString("finalOutputFormatSetting", FinalOutputFormat.JPEG.name))
        } catch (_: Exception) { FinalOutputFormat.JPEG }

        val displayRotation = jobJson.optInt("displayRotation", 0)
        val rawSpeedMode = jobJson.optString("rawSpeedMode", "SAFE")

        // Processing params are stored as JSON string in job.json
        val processingParamsJson = jobJson.optString("processingParams", "")

        // Verify that we are on background thread with correct priority
        val priority = android.os.Process.getThreadPriority(android.os.Process.myTid())
        if (priority != android.os.Process.THREAD_PRIORITY_BACKGROUND) {
            Log.w("KeplerBackgroundExecutor", "Heavy work not on background priority: $priority")
        }

        when (jobKind) {
            KeplerActiveOperationKind.PROCESSING_YUV -> {
                try {
                    Log.i("KeplerBackgroundExecutor", "Processing YUV/SR ${jobDir.name} mode=$captureMode format=$finalOutputFormat rotation=$displayRotation")
                } catch (e: Exception) {
                    Log.e("KeplerBackgroundExecutor", "YUV job failed ${jobDir.name}", e)
                }
            }
            KeplerActiveOperationKind.PROCESSING_RAW -> {
                try {
                    Log.i("KeplerBackgroundExecutor", "Processing RAW ${jobDir.name} speedMode=$rawSpeedMode format=$finalOutputFormat")
                } catch (e: Exception) {
                    Log.e("KeplerBackgroundExecutor", "RAW job failed ${jobDir.name}", e)
                }
            }
            else -> Log.i("KeplerBackgroundExecutor", "Executed ${jobDir.name} kind $jobKind")
        }
    }
}
