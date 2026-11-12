package com.homepanel.kiosk

/**
 * Rayons d'épicerie : range les articles d'une liste par section du magasin, d'après des mots courants
 * (français et anglais). Une liste qui n'est pas une liste d'épicerie (« Appeler le plombier ») n'est pas regroupée.
 */
object Aisles {
    /** Sections dans l'ordre d'un parcours de magasin : (nom, mots reconnus, sans accents). */
    private val SECTIONS: List<Pair<String, List<String>>> = listOf(
        "🥕  Fruits et légumes" to listOf("pomme", "banane", "orange", "citron", "lime", "fraise", "bleuet", "framboise", "raisin", "poire", "peche", "melon", "ananas", "avocat", "tomate",
            "laitue", "salade", "epinard", "carotte", "oignon", "ail", "patate", "pomme de terre", "poivron", "concombre", "brocoli", "chou", "celeri", "champignon", "courgette", "zucchini",
            "mais", "haricot vert", "persil", "coriandre", "basilic", "gingembre", "fruit", "legume", "apple", "banana", "lemon", "strawberr", "blueberr", "grape", "pear", "peach",
            "tomato", "lettuce", "spinach", "carrot", "onion", "garlic", "potato", "pepper", "cucumber", "broccoli", "cabbage", "celery", "mushroom", "herb", "vegetable"),
        "🥛  Produits laitiers et œufs" to listOf("lait", "creme", "beurre", "fromage", "yogourt", "yaourt", "oeuf", "cheddar", "mozzarella", "parmesan", "feta", "margarine",
            "milk", "cream", "butter", "cheese", "yogurt", "egg"),
        "🥩  Viandes et poissons" to listOf("poulet", "boeuf", "porc", "veau", "agneau", "dinde", "jambon", "bacon", "saucisse", "steak", "viande", "poisson", "saumon", "thon", "crevette",
            "tofu", "chicken", "beef", "pork", "turkey", "ham", "sausage", "meat", "fish", "salmon", "tuna", "shrimp"),
        "🥖  Boulangerie" to listOf("pain", "baguette", "bagel", "croissant", "tortilla", "pita", "muffin", "brioche", "bread", "bun", "wrap"),
        "❄️  Surgelés" to listOf("surgele", "congele", "creme glacee", "glace", "pizza", "frite", "frozen", "ice cream", "fries"),
        "🥤  Boissons" to listOf("jus", "cafe", "the ", "tisane", "eau", "biere", "vin", "boisson", "liqueur", "soda", "juice", "coffee", "tea", "water", "beer", "wine", "drink"),
        "🥫  Épicerie" to listOf("pate", "riz", "farine", "sucre", "sel", "poivre", "huile", "vinaigre", "cereale", "gruau", "avoine", "sauce", "ketchup", "moutarde", "mayonnaise", "soupe",
            "bouillon", "conserve", "lentille", "pois chiche", "haricot", "noix", "amande", "arachide", "beurre d'arachide", "confiture", "miel", "sirop", "chocolat", "biscuit", "craquelin",
            "croustille", "chips", "epice", "levure", "pasta", "rice", "flour", "sugar", "salt", "oil", "vinegar", "cereal", "oat", "soup", "bean", "nut", "jam", "honey", "syrup",
            "chocolate", "cookie", "cracker", "spice"),
        "🏠  Maison et hygiène" to listOf("papier", "essuie-tout", "mouchoir", "savon", "shampo", "dentifrice", "brosse a dent", "deodorant", "lessive", "detergent", "assouplissant", "javel",
            "eponge", "sac ", "sacs ", "poubelle", "pellicule", "aluminium", "couche", "lingette", "rasoir", "pile", "ampoule", "nettoyant", "paper", "tissue", "soap", "toothpaste",
            "laundry", "bleach", "sponge", "bag", "diaper", "wipe", "razor", "batter", "bulb", "cleaner"),
    )

    const val OTHER = "🛒  Autres"

    private fun norm(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace("œ", "oe") + " "

    /** Rayon d'un article, ou null s'il n'est pas reconnu. Le mot doit commencer un mot de l'article (« lait » ne reconnaît pas « balai »). */
    fun of(item: String): String? {
        val n = " " + norm(item)
        // Le mot le plus long gagne : « beurre d'arachide » va à l'épicerie, pas aux produits laitiers.
        var best: String? = null; var bestLen = 0
        for ((name, words) in SECTIONS) for (w in words) if (w.length > bestLen && n.contains(" $w")) { best = name; bestLen = w.length }
        return best
    }

    /**
     * Regroupe [items] par rayon, dans l'ordre du magasin, « Autres » à la fin. Retourne null quand la liste ne ressemble
     * pas à une épicerie : moins de 4 articles, ou moins de la moitié reconnus.
     */
    fun <T> group(items: List<T>, text: (T) -> String): List<Pair<String, List<T>>>? {
        if (items.size < 4) return null
        val by = items.groupBy { of(text(it)) }
        val known = items.size - (by[null]?.size ?: 0)
        if (known * 2 < items.size || by.keys.filterNotNull().size < 2) return null
        val out = ArrayList<Pair<String, List<T>>>()
        for ((name, _) in SECTIONS) by[name]?.let { out.add(name to it) }
        by[null]?.let { out.add(OTHER to it) }
        return out
    }
}
