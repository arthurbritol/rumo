package app.hunter.sync

data class BikeBrand(val name: String, val models: List<String>)

object BikeCatalog {
    val brands: List<BikeBrand> = listOf(
        BikeBrand("Royal Enfield", listOf(
            "Hunter 350", "Meteor 350", "Classic 350", "Bullet 350", "Himalayan",
            "Scram 411", "Interceptor 650", "Continental GT 650", "Super Meteor 650",
        )),
        BikeBrand("Yamaha", listOf(
            "Fazer 250", "Lander 250", "XTZ 150", "MT-03", "MT-07", "MT-09", "Ténéré 700", "XJ6",
        )),
        BikeBrand("Honda", listOf(
            "CB 300F Twister", "CB 500F", "CB 650R", "CG 160", "XRE 300", "NC 750X",
            "Africa Twin", "Bros 160", "PCX 160",
        )),
        BikeBrand("Kawasaki", listOf(
            "Ninja 400", "Z400", "Z650", "Z900", "Versys 650", "Vulcan S",
        )),
        BikeBrand("Triumph", listOf(
            "Speed 400", "Scrambler 400 X", "Street Triple", "Trident 660", "Tiger 660",
            "Bonneville T120", "Speed Twin",
        )),
        BikeBrand("BMW", listOf(
            "G 310 R", "G 310 GS", "F 750 GS", "F 850 GS", "R 1250 GS", "R nineT",
        )),
        BikeBrand("KTM", listOf(
            "Duke 200", "Duke 390", "390 Adventure", "790 Duke", "890 Adventure",
        )),
        BikeBrand("TVS", listOf("Apache RTR 160", "Apache RTR 200", "Ronin", "Jupiter")),
        BikeBrand("Bajaj", listOf("Dominar 160", "Dominar 400", "Pulsar NS200", "Pulsar 220F")),
        BikeBrand("Harley-Davidson", listOf(
            "Iron 883", "Sportster S", "Nightster", "Street Glide", "Fat Boy",
        )),
        BikeBrand("Outra", emptyList()),
    )

    fun models(brand: String): List<String> =
        brands.firstOrNull { it.name == brand }?.models ?: emptyList()
}
