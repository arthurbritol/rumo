package app.hunter.app.ui.onboarding

/** Para onde a primeira abertura leva. */
enum class EntryRoute {
    /** Apresentação já vista: direto para o app. */
    MAIN,

    /** Instalação nova: apresentação, login, permissões e moto. */
    ONBOARDING,

    /** Instalação anterior com dados: direto para o app, com aviso para criar conta (sem apresentação nem login forçado). */
    EXISTING_USER,
}

/**
 * Quem já usava o app (preferência `seeded` ou qualquer viagem, abastecimento, item de manutenção ou local
 * gravado) nunca vê a apresentação nem um login forçado.
 */
fun entryRoute(seen: Boolean, seeded: Boolean, localRows: Int, inProgress: Boolean = false): EntryRoute = when {
    seen -> EntryRoute.MAIN
    // Apresentação interrompida (processo morto): as linhas/`seeded` podem ter vindo da própria apresentação
    // (login sincronizou, cadastro da moto), então continua sendo instalação nova.
    inProgress -> EntryRoute.ONBOARDING
    seeded || localRows > 0 -> EntryRoute.EXISTING_USER
    else -> EntryRoute.ONBOARDING
}
