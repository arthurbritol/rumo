package app.hunter.sync

/** Marcas e modelos de carro mais comuns no Brasil (não exaustivo; "Outra" e "Outro modelo" aceitam texto livre). Mesmo formato do [BikeCatalog]. */
object CarCatalog {
    val brands: List<BikeBrand> = listOf(
        BikeBrand("Chevrolet", listOf("Onix", "Onix Plus", "Prisma", "Tracker", "S10", "Spin", "Cruze", "Montana", "Celta", "Cobalt", "Equinox")),
        BikeBrand("Fiat", listOf("Mobi", "Argo", "Cronos", "Strada", "Uno", "Palio", "Toro", "Pulse", "Fastback", "Siena", "Fiorino")),
        BikeBrand("Volkswagen", listOf("Gol", "Polo", "T-Cross", "Nivus", "Virtus", "Saveiro", "Voyage", "Fox", "Jetta", "Amarok", "Taos")),
        BikeBrand("Ford", listOf("Ka", "Fiesta", "Focus", "EcoSport", "Ranger", "Territory", "Maverick")),
        BikeBrand("Renault", listOf("Kwid", "Sandero", "Logan", "Duster", "Captur", "Oroch", "Clio")),
        BikeBrand("Toyota", listOf("Corolla", "Corolla Cross", "Hilux", "Yaris", "Etios", "SW4", "RAV4")),
        BikeBrand("Honda", listOf("Civic", "HR-V", "City", "Fit", "WR-V", "CR-V")),
        BikeBrand("Hyundai", listOf("HB20", "HB20S", "Creta", "Tucson", "ix35")),
        BikeBrand("Jeep", listOf("Renegade", "Compass", "Commander")),
        BikeBrand("Nissan", listOf("Kicks", "Versa", "March", "Sentra", "Frontier")),
        BikeBrand("Peugeot", listOf("208", "2008", "207", "308", "3008")),
        BikeBrand("Citroën", listOf("C3", "C4 Cactus", "C3 Aircross", "Aircross")),
        BikeBrand("BYD", listOf("Dolphin", "Dolphin Mini", "Song Plus", "Seal", "Yuan Plus")),
        BikeBrand("Mitsubishi", listOf("L200 Triton", "Pajero Sport", "ASX", "Eclipse Cross", "Outlander")),
        BikeBrand("Kia", listOf("Sportage", "Sorento", "Cerato", "Picanto", "Stonic", "Soul")),
        BikeBrand("Outra", emptyList()),
    )

    fun models(brand: String): List<String> =
        brands.firstOrNull { it.name == brand }?.models ?: emptyList()
}
