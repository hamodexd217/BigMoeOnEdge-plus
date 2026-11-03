package com.bigmoe.onedge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFamiliesTest {
    @Test
    fun recognisesLiquidAndFlintArchitectures() {
        assertEquals(ModelFamilies.LIQUID_LFM2, ModelFamilies.familyOf("lfm2"))
        assertEquals(ModelFamilies.LIQUID_LFM2, ModelFamilies.familyOf("LFM2MoE"))
        assertEquals(ModelFamilies.LIQUID_LFM3, ModelFamilies.familyOf("lfm3"))
        assertEquals(ModelFamilies.FLINT, ModelFamilies.familyOf("flint"))
        assertNull(ModelFamilies.familyOf("llama"))
    }

    @Test
    fun extractsTheArchitectureFromTheNativeError() {
        assertEquals("lfm4", ModelFamilies.unknownArchitectureIn("llama_model_load: unknown model architecture: 'lfm4'"))
        assertNull(ModelFamilies.unknownArchitectureIn("failed to load model: /x.gguf"))
    }

    @Test
    fun explainsUnknownArchitecturesAndLeavesOtherErrorsAlone() {
        val msg = ModelFamilies.explainLoadError("unknown model architecture: 'lfm4'")
        assertTrue(msg.contains("'lfm4'"))
        assertTrue(msg.contains("LFM2"))
        assertTrue(msg.contains("flint"))
        assertEquals("out of memory", ModelFamilies.explainLoadError("out of memory"))
        assertNotNull(ModelFamilies.explainLoadError("unknown model architecture: 'something'"))
    }

    @Test
    fun npuMappingFailuresGetAnActionableHint() {
        val msg = ModelFamilies.explainLoadError("prefill device HTP0: the device-placed reservation decode failed (ggml-hex: fastrpc_mmap failed (see log for details))")
        assertTrue(msg.contains("fastrpc_mmap failed"))
        assertTrue(msg.contains("Turn off NPU prefill"))
        assertEquals("out of memory", ModelFamilies.explainLoadError("out of memory"))
    }
}
