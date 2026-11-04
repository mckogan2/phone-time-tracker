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
        val max = if (level >= 2) 1000 else 200
        val kinds = if (level >= 2) 2 else 3
        return when (rng.nextInt(kinds)) {
            0 -> {
                val a = rng.nextInt(1, max)
                val b = rng.nextInt(1, max - a + 1)
                Question("$a + $b = ?", a + b)
            }
            1 -> {
                val a = rng.nextInt(1, max + 1)
                val b = rng.nextInt(1, a + 1)
                Question("$a − $b = ?", a - b)
            }
            else -> {
                val a = rng.nextInt(2, 6)
                val b = rng.nextInt(2, 11)
                Question("$a × $b = ?", a * b)
            }
        }
    }
}
