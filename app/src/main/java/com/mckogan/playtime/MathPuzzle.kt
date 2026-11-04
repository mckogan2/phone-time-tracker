package com.mckogan.playtime

import kotlin.random.Random

/**
 * Questions a kid types an answer to, to earn time after it runs out.
 * Level 1: numbers up to 200 with +, −, and small ×. Level 2: numbers up to 1000 with +, −.
 * Every question is random, so there is no fixed list to memorize.
 */
object MathPuzzle {

    class Question(val text: String, val answer: Int)

    fun make(level: Int, rng: Random = Random.Default): Question {
        if (level >= 2) return makeLevel2(rng)
        return when (rng.nextInt(3)) {
            // Addition with a sum up to 200.
            0 -> {
                val a = rng.nextInt(1, 200)
                val b = rng.nextInt(1, 200 - a + 1)
                Question("$a + $b = ?", a + b)
            }
            // Subtraction from up to 100, with a small difference (1 to 10).
            1 -> {
                val a = rng.nextInt(2, 101)
                val gap = rng.nextInt(1, minOf(10, a - 1) + 1)
                Question("$a − ${a - gap} = ?", gap)
            }
            // Small multiplication, up to 10 × 5 = 50.
            else -> {
                val a = rng.nextInt(2, 11)
                val b = rng.nextInt(2, 6)
                Question("$a × $b = ?", a * b)
            }
        }
    }

    /** Level 2: addition and subtraction with numbers up to 1000. */
    private fun makeLevel2(rng: Random): Question {
        return if (rng.nextInt(2) == 0) {
            val a = rng.nextInt(1, 1000)
            val b = rng.nextInt(1, 1000 - a + 1)
            Question("$a + $b = ?", a + b)
        } else {
            val a = rng.nextInt(1, 1001)
            val b = rng.nextInt(1, a + 1)
            Question("$a − $b = ?", a - b)
        }
    }
}
