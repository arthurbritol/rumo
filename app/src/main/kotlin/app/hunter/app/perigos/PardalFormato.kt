package app.hunter.app.perigos

/** Lógica pura (sem Android) da seção "Dados de radares" do Perfil. */
object PardalFormato {
    const val URL_PARDAL = "https://github.com/murkiriel/pardal"
    const val URL_OSM = "https://www.openstreetmap.org/copyright"

    const val ATRIBUICAO =
        "Dados de radares: Pardal (github.com/murkiriel/pardal), base derivada do OpenStreetMap sob licença ODbL 1.0. " +
            "© colaboradores do OpenStreetMap. Fontes: DNIT, PRF, ANTT, Inmetro, órgãos estaduais e municipais. " +
            "A sinalização da via sempre prevalece."

    private const val DIA_MS = 24L * 3600 * 1000

    /** Dias inteiros desde [baixadaEm] (nunca negativo). */
    fun idadeDias(baixadaEm: Long, agora: Long): Int = (maxOf(0L, agora - baixadaEm) / DIA_MS).toInt()

    /** "hoje", "há 1 dia", "há 5 dias". */
    fun textoIdade(baixadaEm: Long, agora: Long): String = when (val d = idadeDias(baixadaEm, agora)) {
        0 -> "hoje"
        1 -> "há 1 dia"
        else -> "há $d dias"
    }

    /** 3412 -> "3.412" (separador de milhar do pt-BR, sem depender do locale do aparelho). */
    fun milhar(n: Int): String = n.toString().reversed().chunked(3).joinToString(".").reversed()

    /** Linha da lista de estados baixados. */
    fun linhaUf(s: PardalUfStatus, agora: Long): String {
        if (s.baixando) return "${s.uf}: baixando…"
        val base = if (s.baixadaEm == null) "${s.uf}: não baixado" else {
            val idade = textoIdade(s.baixadaEm, agora)
            val pontos = "${milhar(s.contagem)} ${if (s.contagem == 1) "ponto" else "pontos"}"
            val prefixo = if (s.recente) "atualizado $idade" else "desatualizado, $idade"
            "${s.uf}: $prefixo · $pontos"
        }
        return if (s.erro != null) "$base · ${erroCurto(s.erro)}" else base
    }

    private fun erroCurto(e: String): String {
        val t = e.trim().replace('\n', ' ')
        return "falhou (" + (if (t.length > 40) t.take(40).trimEnd() + "…" else t) + ")"
    }

    /** Só estados com algo a mostrar (baixados, baixando ou com erro), em ordem alfabética. */
    fun ordenar(ufs: Collection<PardalUfStatus>): List<PardalUfStatus> = ufs.sortedBy { it.uf }

    /** "cerca de 12 MB", "cerca de 800 KB" ou "um arquivo grande" quando o tamanho é desconhecido. */
    fun tamanho(bytes: Long?): String = when {
        bytes == null || bytes <= 0 -> "tamanho desconhecido"
        bytes >= 1_000_000 -> "cerca de ${maxOf(1L, (bytes + 500_000) / 1_000_000)} MB"
        else -> "cerca de ${maxOf(1L, (bytes + 500) / 1_000)} KB"
    }

    fun tituloPedido(p: PardalPedidoDadosMoveis) = "Usar dados móveis para baixar?"

    fun textoPedido(p: PardalPedidoDadosMoveis) =
        "Sem Wi-Fi agora. Baixar os radares e lombadas de ${p.ufs.joinToString(", ")} (${tamanho(p.bytes)}) usando dados móveis?"

    /** "Espaço usado: 4,2 MB" (soma dos estados baixados). Vírgula decimal, sem depender do locale. */
    fun espacoUsado(ufs: Collection<PardalUfStatus>): String {
        val total = ufs.sumOf { it.bytes }
        val texto = when {
            total <= 0 -> "nenhum"
            total < 100_000 -> "menos de 1 MB"
            else -> "%.1f".format(java.util.Locale.ROOT, total / 1_000_000.0).replace('.', ',') + " MB"
        }
        return "Espaço usado: $texto"
    }

    /**
     * O diálogo de dados móveis aparece só com pedido pendente e nunca navegando ou gravando. O pedido só nasce de uma ação manual
     * (Perfil), então navegar/gravar quase nunca coincide com ele; fica guardado e aparece depois.
     */
    fun mostrarPedido(pedido: PardalPedidoDadosMoveis?, navegando: Boolean, gravando: Boolean): Boolean =
        pedido != null && !navegando && !gravando

    /** "Atualizar agora" atua nos estados já baixados (não nos que só falharam ou estão baixando). */
    fun ufsParaAtualizar(ufs: Collection<PardalUfStatus>): List<String> =
        ordenar(ufs).filter { it.baixadaEm != null && !it.baixando }.map { it.uf }
}
