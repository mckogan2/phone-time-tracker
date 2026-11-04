package com.mckogan.playtime

import kotlin.random.Random

/**
 * Questions a kid answers to earn time. Every question is random.
 * Typed answers: addition, subtraction, small multiplication. Level 1: addition up to 200, subtraction from up to 100
 * taking away at most 20, multiplication up to 10 x 5. Level 2: addition up to 400, subtraction within the same hundred.
 * Picture questions (four choices): a picture stands for a hidden number, e.g. "10 + 😀 = 13". Numbers up to 50.
 */
object MathPuzzle {

    /** [options] is null for a typed answer; otherwise the kid picks one of them, and [answer] is the right one. */
    class Question(val text: String, val answer: String, val options: List<String>? = null)

    private val PICTURES = listOf("😀", "🐶", "🍎", "⭐", "🚗", "🌸", "🐟", "🎈")

    fun make(level: Int, rng: Random = Random.Default): Question = when (rng.nextInt(4)) {
        0 -> addition(if (level >= 2) 400 else 200, rng)
        1 -> if (level >= 2) subtractionSameHundred(rng) else subtractionFrom100(rng)
        2 -> multiply(rng)
        else -> picture(rng)
    }

    /**
     * A picture stands for a hidden number. Numbers stay up to 50. In a subtraction the number taken away
     * is at most 20: "87 − 😀 = 68" is fine, "87 − 😀 = 58" is not.
     */
    private fun picture(rng: Random): Question {
        val image = PICTURES[rng.nextInt(PICTURES.size)]
        val hidden: Int
        val text: String
        if (rng.nextBoolean()) {
            hidden = rng.nextInt(1, 11)
            val a = rng.nextInt(1, 51 - hidden)
            text = "$a + $image = ${a + hidden}"
        } else {
            hidden = rng.nextInt(1, 21)
            val result = rng.nextInt(1, 51 - hidden)
            text = "${result + hidden} − $image = $result"
        }
        val wrong = (1..20).filter { it != hidden }.shuffled(rng).take(3).map { it.toString() }
        return Question(text, hidden.toString(), (wrong + hidden.toString()).shuffled(rng))
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
