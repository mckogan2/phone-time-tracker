package com.mckogan.playtime

import kotlin.random.Random

/**
 * Questions a kid answers to earn time. Every question is random.
 * Typed answers: addition, subtraction, small multiplication.
 * Choice answers (four options): a picture stands for a hidden number, and shape patterns to continue.
 */
object MathPuzzle {

    /** [options] is null for a typed answer; otherwise the kid picks one, and [answer] is the right one. */
    class Question(val text: String, val answer: String, val options: List<String>? = null)

    private val PICTURES = listOf("😀", "🐶", "🍎", "⭐", "🚗", "🌸", "🐟", "🎈")
    /** Arrows in clockwise order: up, right, down, left. */
    private val ARROWS = listOf("▲", "▶", "▼", "◀")
    private val SHAPES = listOf("●", "■", "▲", "★", "◆")

    fun make(level: Int, rng: Random = Random.Default): Question = when (rng.nextInt(6)) {
        0 -> addition(if (level >= 2) 400 else 200, rng)
        1 -> if (level >= 2) subtractionSameHundred(rng) else subtractionFrom100(rng)
        2 -> multiply(rng)
        3 -> picture(rng)
        4 -> arrows(rng)
        else -> alternating(rng)
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

    /**
     * A picture stands for a hidden number: "10 + 😀 = 13". Numbers stay up to 50.
     * In a subtraction the number taken away is at most 20: "87 − 😀 = 68" is fine, "87 − 😀 = 58" is not.
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
        return choice(text, hidden.toString(), wrong, rng)
    }

    /** Arrows turning one way, e.g. ▲ ▶ ▼ ?  (next: ◀). */
    private fun arrows(rng: Random): Question {
        val step = if (rng.nextBoolean()) 1 else -1
        val start = rng.nextInt(4)
        val seq = (0 until 4).map { ARROWS[Math.floorMod(start + step * it, 4)] }
        val text = "${seq[0]} ${seq[1]} ${seq[2]} ?"
        return choice(text, seq[3], ARROWS.filter { it != seq[3] }, rng)
    }

    /** Two shapes taking turns, e.g. ● ■ ● ■ ●  ?  (next: ■). */
    private fun alternating(rng: Random): Question {
        val (first, second) = SHAPES.shuffled(rng).take(2)
        val text = "$first $second $first $second $first ?"
        val wrong = SHAPES.filter { it != second }.shuffled(rng).take(3)
        return choice(text, second, wrong, rng)
    }

    private fun choice(text: String, answer: String, wrong: List<String>, rng: Random): Question =
        Question(text, answer, (wrong + answer).shuffled(rng))
}
