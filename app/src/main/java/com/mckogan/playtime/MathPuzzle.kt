package com.mckogan.playtime

import kotlin.random.Random

/**
 * Arithmetic questions a kid types an answer to, to earn time. Every question is random.
 * Level 1: addition up to 200, subtraction from up to 100 taking away at most 20, small multiplication (up to 10 x 5).
 * Level 2: addition up to 400, subtraction within the same hundred, the same multiplication.
 */
object MathPuzzle {

    class Question(val text: String, val answer: String)

    fun make(level: Int, rng: Random = Random.Default): Question = when (rng.nextInt(3)) {
        0 -> addition(if (level >= 2) 400 else 200, rng)
        1 -> if (level >= 2) subtractionSameHundred(rng) else subtractionFrom100(rng)
        else -> multiply(rng)
    }

    /** Sums up to [max]. */
    private fun addition(max: Int, rng: Random): Question {
        val a = rng.nextInt(1, max)
        val b = rng.nextInt(1, max - a + 1)
        return Question("$a + $b = ?", (a + b).toString())
    }

    /** Subtraction from up to 100, taking away at most 20: "87 − 19 = ?" is fine, "87 − 21" is not. */
    private fun subtractionFrom100(rng: Random): Question {
        val taken = rng.nextInt(1, 21)
        val a = rng.nextInt(taken + 1, 101)
        return Question("$a − $taken = ?", (a - taken).toString())
    }

    /** Both numbers in the same hundred, e.g. 237 − 215. */
    private fun subtractionSameHundred(rng: Random): Question {
        val base = rng.nextInt(0, 10) * 100
        val a = rng.nextInt(base + 2, base + 100)
        val b = rng.nextInt(base + 1, a)
        return Question("$a − $b = ?", (a - b).toString())
    }

    /** Small multiplication, up to 10 × 5 = 50. */
    private fun multiply(rng: Random): Question {
        val a = rng.nextInt(2, 11)
        val b = rng.nextInt(2, 6)
        return Question("$a × $b = ?", (a * b).toString())
    }
}
