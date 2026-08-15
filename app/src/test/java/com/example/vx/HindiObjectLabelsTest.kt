package com.example.vx

import org.junit.Assert.assertEquals
import org.junit.Test

class HindiObjectLabelsTest {
    @Test
    fun commonCocoLabelsAreHindi() {
        assertEquals("व्यक्ति", HindiObjectLabels.labelFor("person"))
        assertEquals("साइकिल", HindiObjectLabels.labelFor("bicycle"))
        assertEquals("कुत्ता", HindiObjectLabels.labelFor("dog"))
        assertEquals("मोबाइल फोन", HindiObjectLabels.labelFor("cell phone"))
        assertEquals("फूलदान", HindiObjectLabels.labelFor("vase"))
    }

    @Test
    fun unknownModelLabelIsNotFabricated() {
        assertEquals("new-model-label", HindiObjectLabels.labelFor("new-model-label"))
    }
}
