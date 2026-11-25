package com.robomori.components

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TableTest {
    private val keys = listOf("Name", "Status", "Type", "Size")
    private val shares = mapOf("Name" to .5f, "Status" to .1f, "Type" to .2f, "Size" to .2f)

    @Test fun `moving the line between two columns moves share between just those two`() {
        // The line between Status and Type moves right: Status gains what Type gives up.
        assertEquals(mapOf("Name" to .5f, "Status" to .15f, "Type" to .15f, "Size" to .2f),
            resizedBetween(keys, shares, index = 2, left = .15f).mapValues { (_, share) -> Math.round(share * 100) / 100f })
    }

    @Test fun `the first column resizes like any other`() {
        assertEquals(mapOf("Name" to .4f, "Status" to .2f, "Type" to .2f, "Size" to .2f),
            resizedBetween(keys, shares, index = 1, left = .4f).mapValues { (_, share) -> Math.round(share * 100) / 100f })
    }

    @Test fun `typing selects the first name starting with the letters typed together`() {
        var time = 0L
        val typeAhead = TableTypeAhead { time }
        val names = listOf("android", "Robomori", "robots.txt", "zebra")

        assertEquals(1, typeAhead.type('r', names))
        time += 100
        assertEquals(1, typeAhead.type('O', names))
        time += 100
        assertEquals(1, typeAhead.type('b', names))
        time += 100
        assertEquals(1, typeAhead.type('o', names))
        time += 100
        assertEquals(2, typeAhead.type('t', names))
        time += 100
        assertEquals(null, typeAhead.type('x', names))
    }

    @Test fun `a pause starts the letters over`() {
        var time = 0L
        val typeAhead = TableTypeAhead { time }
        val names = listOf("android", "zebra")

        typeAhead.type('z', names)
        time += 1500

        assertEquals(0, typeAhead.type('a', names))
    }
}
