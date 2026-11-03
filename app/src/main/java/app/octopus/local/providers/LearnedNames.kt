package app.octopus.local.providers

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What the user matched by hand for one restaurant and meal: its Keeta meal name, Keeta choices that
 * HungerStation does not have (picked as the user chose), and Keeta choice names for HungerStation ones.
 * Kept only on this phone; it changes which Keeta rows are read and picked, never a price or cart check. */
class LearnedNames(private val file: File) {
    data class Choices(val extras: Set<String> = emptySet(), val aliases: Map<String, String> = emptyMap(),
        /** HungerStation choices this Keeta meal has no place for; the user agreed to leave them out. */
        val ignored: Set<String> = emptySet())
    private val lock = Any()
    private val meals = mutableMapOf<String, String>()
    private val choices = mutableMapOf<String, Choices>()
    init {
        runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            json.optJSONObject("meals")?.let { m -> m.keys().forEach { meals[it] = m.getString(it) } }
            json.optJSONObject("choices")?.let { c -> c.keys().forEach { k ->
                val o = c.getJSONObject(k)
                val extras = o.optJSONArray("extras")?.let { a -> (0 until a.length()).map(a::getString).toSet() } ?: emptySet()
                val aliases = o.optJSONObject("aliases")?.let { a -> a.keys().asSequence().associateWith(a::getString) } ?: emptyMap()
                val ignored = o.optJSONArray("ignored")?.let { a -> (0 until a.length()).map(a::getString).toSet() } ?: emptySet()
                choices[k] = Choices(extras, aliases, ignored)
            } }
        }
    }

    private fun key(restaurant: String, meal: String) = KeetaNameText.loose(restaurant) + "\u0001" + KeetaNameText.loose(meal)
    private fun valid(text: String) = text.isNotBlank() && text.length <= 300 && '\n' !in text

    fun keetaMeal(restaurant: String, hungerMeal: String): String? = synchronized(lock) { meals[key(restaurant, hungerMeal)] }
    fun choices(restaurant: String, hungerMeal: String): Choices = synchronized(lock) { choices[key(restaurant, hungerMeal)] ?: Choices() }

    fun rememberKeetaMeal(restaurant: String, hungerMeal: String, keetaMeal: String) {
        if (!valid(restaurant) || !valid(hungerMeal) || !valid(keetaMeal)) return
        synchronized(lock) { meals[key(restaurant, hungerMeal)] = keetaMeal.trim(); save() }
    }
    /** [keetaChoice] is picked in Keeta for a group HungerStation does not have ([hungerChoice] null), or
     * names [hungerChoice] in Keeta. */
    fun rememberChoice(restaurant: String, hungerMeal: String, keetaChoice: String, hungerChoice: String?) {
        if (!valid(restaurant) || !valid(hungerMeal) || !valid(keetaChoice) || (hungerChoice != null && !valid(hungerChoice))) return
        synchronized(lock) {
            val k = key(restaurant, hungerMeal)
            val now = choices[k] ?: Choices()
            choices[k] = if (hungerChoice == null) now.copy(extras = now.extras + keetaChoice.trim())
                else now.copy(aliases = now.aliases + (keetaChoice.trim() to hungerChoice.trim()))
            save()
        }
    }

    fun rememberIgnored(restaurant: String, hungerMeal: String, hungerChoices: Collection<String>) {
        if (!valid(restaurant) || !valid(hungerMeal) || hungerChoices.isEmpty() || hungerChoices.any { !valid(it) }) return
        synchronized(lock) {
            val k = key(restaurant, hungerMeal)
            val now = choices[k] ?: Choices()
            choices[k] = now.copy(ignored = now.ignored + hungerChoices.map(String::trim))
            save()
        }
    }

    private fun save() = runCatching {
        val c = JSONObject()
        choices.forEach { (k, v) -> c.put(k, JSONObject().put("extras", JSONArray(v.extras.toList())).put("aliases", JSONObject(v.aliases as Map<*, *>))
            .put("ignored", JSONArray(v.ignored.toList()))) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject().put("meals", JSONObject(meals as Map<*, *>)).put("choices", c).toString(), Charsets.UTF_8)
        tmp.renameTo(file)
    }

    companion object {
        /** Set once by the app; readers built anywhere look up the user's matches through it. */
        @Volatile var shared: LearnedNames? = null
        fun choicesFor(restaurant: String, hungerMeal: String): Choices = shared?.choices(restaurant, hungerMeal) ?: Choices()
    }
}

/** A Keeta required group with no row named like the HungerStation choices: the user picks its row once. */
data class ChoiceQuestion(val restaurant: String, val hungerMeal: String, val group: String, val rows: List<String>,
    val unmatchedHunger: List<String>)

/** A Keeta menu without the HungerStation meal's name: the closest Keeta meals, for the user to choose. */
data class MealQuestion(val restaurant: String, val hungerMeal: String, val candidates: List<String>)

object MealCandidates {
    private val generic = setOf("وجبة", "كومبو", "-", "مع", "و")
    private fun words(value: String) = KeetaNameText.loose(value).replace("-", " ").replace(",", " ")
        .split(' ').filter { it.isNotBlank() && it !in generic }.map { if (it in setOf("ساندويتش", "سندويتش", "سندوتش")) "ساندوتش" else it }.toSet()

    /** Up to four Keeta names sharing the most words with [wanted]; none when nothing is shared. */
    fun closest(wanted: String, seen: Collection<String>): List<String> {
        val target = words(wanted)
        return seen.map(String::trim).filter { it.isNotBlank() && '\n' !in it && it.length <= 300 }.distinct()
            .map { it to (words(it) intersect target).size }.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(4).map { it.first }
    }
}
