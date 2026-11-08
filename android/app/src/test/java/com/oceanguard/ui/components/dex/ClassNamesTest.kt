package com.oceanguard.ai.ui.components.dex

import com.oceanguard.ai.inference.DebrisClasses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClassNamesTest {

    @Test
    fun everyDetectorClassHasATranslation() {
        DebrisClasses.NAMES.forEach { assertNotNull(it, classNameRes(it)) }
    }

    @Test
    fun matchingIgnoresCaseAndSpaces() {
        assertEquals(classNameRes("Fishing_Net"), classNameRes("fishing net"))
    }

    @Test
    fun unknownClassHasNoResource() {
        assertNull(classNameRes("unicorn"))
    }
}
