package com.mckogan.playtime

import kotlin.random.Random

/**
 * Questions a kid types an answer to, to earn time after it runs out. Every question is random.
 * Level 1: addition with sums up to 200, subtraction from up to 100 with a small difference,
 * and small multiplication (up to 10 × 5).
 * Level 2: addition with sums up to 400, subtraction within the same hundred, and the same multiplication.
 */
object MathPuzzle {

    class Question(val text: String, val answer: Int)

    fun make(level: Int, rng: Random = Random.Default): Question {
        return if (level >= 2) makeLevel2(rng) else makeLevel1(rng)
    }

    private fun makeLevel1(rng: Random): Question = when (rng.nextInt(3)) {
        0 -> addUpTo(200, rng)
        1 -> {
            // Subtraction from up to 100, with a difference of 1 to 10.
            val a = rng.nextInt(2, 101)
            val gap = rng.nextInt(1, minOf(10, a - 1) + 1)
            Question("$a − ${a - gap} = ?", gap)
        }
        else -> multiply(rng)
    }

    private fun makeLevel2(rng: Random): Question = when (rng.nextInt(3)) {
        0 -> addUpTo(400, rng)
        1 -> {
            // Both numbers in the same hundred, e.g. 237 − 215.
            val base = rng.nextInt(0, 10) * 100
            val a = rng.nextInt(base + 2, base + 100)
            val b = rng.nextInt(base + 1, a)
            Question("$a − $b = ?", a - b)
        }
        else -> multiply(rng)
    }

    private fun addUpTo(max: Int, rng: Random): Question {
        val a = rng.nextInt(1, max)
        val b = rng.nextInt(1, max - a + 1)
        return Question("$a + $b = ?", a + b)
    }

    /** Small multiplication, up to 10 × 5 = 50. */
    private fun multiply(rng: Random): Question {
        val a = rng.nextInt(2, 11)
        val b = rng.nextInt(2, 6)
        return Question("$a × $b = ?", a * b)
    }
}
