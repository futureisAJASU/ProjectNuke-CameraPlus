
package com.projectnuke.keplernightlab

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

class Utf8HygieneTest {
    @Test
    fun phaseA_KotlinFiles_areStrictUtf8_andNoReplacementChar() {
        val projectRoot = File(System.getProperty("user.dir") ?: ".")
        // Try to locate project root by searching for settings.gradle
        var root: File? = projectRoot
        while (root != null && !File(root, "settings.gradle.kts").exists() && !File(root, "settings.gradle").exists()) {
            root = root.parentFile
        }
        if (root == null) root = projectRoot
        val files = listOf(
            "app/src/main/java/com/projectnuke/keplernightlab/NightFusionPipeline.kt",
            "app/src/main/java/com/projectnuke/keplernightlab/RawFusionExport.kt",
            "app/src/main/java/com/projectnuke/keplernightlab/SuperResolutionFusion.kt",
            "app/src/main/java/com/projectnuke/keplernightlab/CameraPipelineUiOrchestrator.kt"
        ).map { File(root, it) }

        for (file in files) {
            assertTrue("File should exist: ${file.path}", file.exists())
            val bytes = file.readBytes()
            // Strict UTF-8 decode: must not throw
            try {
                val decoder = Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                decoder.decode(ByteBuffer.wrap(bytes))
            } catch (e: Exception) {
                fail("File ${file.path} is not valid strict UTF-8: ${e.message}")
            }
            val text = file.readText(Charsets.UTF_8)
            // Detect U+FFFD replacement character which indicates prior decode error or corrupted source
            assertFalse("File ${file.path} contains U+FFFD replacement character (corrupted UTF-8)", text.contains("\uFFFD"))
            // Also ensure no literal ?? that was used as mojibake for em dash in Phase 6 (should be —)
            // We only check for the specific corrupted patterns introduced in Phase 6, not historical ??
            if (file.name == "RawFusionExport.kt") {
                assertFalse("File ${file.path} still contains Phase-6 mojibake ?? for em dash", text.contains(" ??whether") || text.contains(" ??the"))
            }
        }
    }

    @Test
    fun phaseA_userFacingMessages_areFormalKorean() {
        val root = File(System.getProperty("user.dir") ?: ".").let {
            var r: File? = it
            while (r != null && !File(r, "settings.gradle.kts").exists() && !File(r, "settings.gradle").exists()) r = r.parentFile
            r ?: it
        }
        val yuv = File(root, "app/src/main/java/com/projectnuke/keplernightlab/NightFusionPipeline.kt").readText(Charsets.UTF_8)
        assertTrue("YUV CaptureStageComplete should be formal Korean", yuv.contains("촬영이 완료되었습니다. 결과를 처리하고 있습니다."))
        assertTrue("YUV lane failure should be formal Korean", yuv.contains("백그라운드 처리 등록에 실패했습니다. 캐시를 보존했습니다. 나중에 다시 처리할 수 있습니다."))
        assertFalse("YUV should not contain mojibake", yuv.contains("촬영???"))

        val raw = File(root, "app/src/main/java/com/projectnuke/keplernightlab/RawFusionExport.kt").readText(Charsets.UTF_8)
        assertTrue("RAW CaptureStageComplete should be formal Korean", raw.contains("촬영이 완료되었습니다. 결과를 처리하고 있습니다."))
        assertTrue("RAW should have correct RAW capture message", raw.contains("RAW 캡처 중입니다. 기기를 움직이지 마세요."))
        assertTrue("RAW lane should be formal Korean", raw.contains("백그라운드 처리 등록에 실패했습니다. 캐시를 보존했습니다. 나중에 다시 처리할 수 있습니다."))

        val sr = File(root, "app/src/main/java/com/projectnuke/keplernightlab/SuperResolutionFusion.kt").readText(Charsets.UTF_8)
        assertTrue("SR CaptureStageComplete should be formal Korean", sr.contains("촬영이 완료되었습니다. 결과를 처리하고 있습니다."))

        val orch = File(root, "app/src/main/java/com/projectnuke/keplernightlab/CameraPipelineUiOrchestrator.kt").readText(Charsets.UTF_8)
        assertFalse("Orchestrator should not contain stale Pipeline busy", orch.contains("Pipeline busy"))
        assertTrue("Orchestrator should have capture-resource-specific Korean", orch.contains("촬영 리소스가 사용 중입니다."))
    }
}
