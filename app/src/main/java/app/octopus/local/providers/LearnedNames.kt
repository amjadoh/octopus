package app.octopus.local.providers

import org.json.JSONObject
import java.io.File

/** Meal names the user matched by hand for one restaurant: the HungerStation meal and its Keeta meal.
 * Kept only on this phone; it changes which Keeta row is read as the meal, never a price or cart check. */
class LearnedNames(private val file: File) {
    private val lock = Any()
    private val meals: MutableMap<String, String> = runCatching {
        val json = JSONObject(file.readText(Charsets.UTF_8)).optJSONObject("meals") ?: JSONObject()
        json.keys().asSequence().associateWith { json.getString(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun key(restaurant: String, meal: String) = KeetaNameText.loose(restaurant) + "\u0001" + KeetaNameText.loose(meal)

    fun keetaMeal(restaurant: String, hungerMeal: String): String? = synchronized(lock) { meals[key(restaurant, hungerMeal)] }

    fun rememberKeetaMeal(restaurant: String, hungerMeal: String, keetaMeal: String) {
        if (restaurant.isBlank() || hungerMeal.isBlank() || keetaMeal.isBlank() || keetaMeal.length > 300 || '\n' in keetaMeal) return
        synchronized(lock) {
            meals[key(restaurant, hungerMeal)] = keetaMeal.trim()
            runCatching {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(JSONObject().put("meals", JSONObject(meals as Map<*, *>)).toString(), Charsets.UTF_8)
                tmp.renameTo(file)
            }
        }
    }
}

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
