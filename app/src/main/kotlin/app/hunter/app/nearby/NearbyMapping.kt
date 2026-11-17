package app.hunter.app.nearby

import app.hunter.model.Geo
import app.hunter.nearby.Distancia
import app.hunter.nearby.MotivoDenuncia
import app.hunter.nearby.MotivoFim
import app.hunter.nearby.Parada
import app.hunter.nearby.Participante
import app.hunter.nearby.Proposta
import app.hunter.nearby.Ritmo
import app.hunter.nearby.Vizinho
import java.util.Locale

// Funções puras (sem Android) do Rolê por perto: propriedades do mapa e textos em pt-BR.

/** Nomes das imagens de moto no estilo do mapa: uma por variante, não por pessoa. */
const val RIDER_ICON = "rider"
const val RIDER_ICON_COMBINA = "rider-combina"

/** Propriedades de uma moto anônima no mapa. `id` é a sessão aleatória; `rumo` nulo vira 0 (aponta para o norte). */
data class RiderFeature(
    val id: String,
    val lat: Double,
    val lon: Double,
    val rumo: Double,
    val destaque: Boolean,
    val icon: String,
)

fun Vizinho.toRiderFeature(): RiderFeature = RiderFeature(
    id = sessao,
    lat = lat,
    lon = lon,
    rumo = rumo ?: 0.0,
    destaque = combina,
    icon = if (combina) RIDER_ICON_COMBINA else RIDER_ICON,
)

/** Imagem do marcador do próprio usuário: a moto laranja enquanto procura rolê, o marcador de sempre fora disso. */
const val OWN_ICON_SEARCHING = "rider-self"
const val OWN_ICON_DEFAULT = "me"

fun ownMarkerIcon(procurando: Boolean): String = if (procurando) OWN_ICON_SEARCHING else OWN_ICON_DEFAULT

private val PT_BR = Locale.forLanguageTag("pt-BR")

/** "ninguém por perto ainda", "1 pessoa por perto", "N pessoas por perto". */
fun pessoasPorPerto(n: Int): String = when {
    n <= 0 -> "ninguém por perto ainda"
    n == 1 -> "1 pessoa por perto"
    else -> "$n pessoas por perto"
}

/** Texto da faixa no mapa. */
fun faixaProcurando(n: Int): String = "Procurando rolê · ${pessoasPorPerto(n)}"

/**
 * Distância arredondada até a posição aproximada de quem está por perto: "a 1,2 km", "a 12 km". A posição já
 * vem com desvio do servidor, então abaixo de 1 km não finge precisão.
 */
fun distanciaAproximada(meLat: Double, meLon: Double, v: Vizinho): String {
    val m = Geo.distanceM(meLat, meLon, v.lat, v.lon)
    return when {
        m < 1000 -> "a menos de 1 km"
        m < 10_000 -> "a " + String.format(PT_BR, "%.1f", m / 1000) + " km"
        else -> "a " + String.format(PT_BR, "%.0f", m / 1000) + " km"
    }
}

val Ritmo.rotulo: String
    get() = when (this) {
        Ritmo.TRANQUILO -> "Tranquilo"
        Ritmo.MODERADO -> "Moderado"
        Ritmo.ANIMADO -> "Animado"
    }

val Distancia.rotulo: String
    get() = when (this) {
        Distancia.ATE50 -> "Até 50 km"
        Distancia.DE50A150 -> "50 a 150 km"
        Distancia.MAIS150 -> "Mais de 150 km"
    }

val Parada.rotulo: String
    get() = when (this) {
        Parada.CAFE -> "Café ou lanche"
        Parada.MIRANTE -> "Mirante"
        Parada.SO_ESTRADA -> "Só estrada"
        Parada.SEM_PARADA -> "Sem parada"
    }

// ---- Encontros ----

/** Nomes da camada e das imagens do ponto de encontro. */
const val PROPOSAL_ICON = "proposal-pin"
const val PROPOSAL_ICON_COMBINA = "proposal-pin-combina"
const val ENCOUNTER_ICON = "encounter-pin"

/** Propriedades de um ponto de proposta no mapa: o texto é "ocupadas de total" (ex.: "2 de 4"). */
data class ProposalFeature(val id: String, val lat: Double, val lon: Double, val label: String, val icon: String)

fun ocupacao(vagasLivres: Int, vagasTotal: Int): String = "${(vagasTotal - vagasLivres).coerceAtLeast(0)} de $vagasTotal"

fun Proposta.toProposalFeature(): ProposalFeature = ProposalFeature(
    id = id,
    lat = lat,
    lon = lon,
    label = ocupacao(vagasLivres, vagasTotal),
    icon = if (combina) PROPOSAL_ICON_COMBINA else PROPOSAL_ICON,
)

/** "1 vaga livre" / "N vagas livres". */
fun vagasLivresTexto(n: Int): String = if (n == 1) "1 vaga livre" else "$n vagas livres"

/** "3,2 km · 12 min"; sem dado ainda: "calculando…". */
fun distEtaTexto(distKm: Double?, etaMin: Double?): String {
    if (distKm == null || etaMin == null) return "calculando…"
    return String.format(PT_BR, "%.1f", distKm) + " km · " + etaMin.toInt() + " min"
}

/** Texto do erro do servidor para a tela (sem termos técnicos); `null` quando não há o que mostrar. */
fun textoDoErro(codigo: String, restanteMs: Long?): String? = when (codigo) {
    "cheio" -> "Esse encontro já está cheio"
    "ja_tem_proposta" -> "Você já tem uma proposta aberta"
    "espera" -> {
        val min = ((restanteMs ?: 0L) + 59_999L) / 60_000L
        when {
            restanteMs == null -> "Espere um pouco para tentar de novo"
            restanteMs < 60_000L -> "Espere menos de 1 min para propor de novo"
            else -> "Espere $min min para propor de novo"
        }
    }
    "inexistente" -> "Esse encontro não existe mais"
    "ja_em_encontro" -> "Você já está em um encontro"
    "indisponivel" -> "Não foi possível propor agora. Tente de novo daqui a pouco"
    "ponto_invalido" -> "Esse ponto não serve para um encontro"
    "nao_procurando", "posicao_implausivel" -> null // tratados em silêncio
    else -> "Algo deu errado. Tente de novo"
}

const val TEXTO_FORA_DA_AREA = "Escolha um ponto dentro da sua área de busca"
const val TEXTO_SEM_RESPOSTA = "O servidor não respondeu. Tente de novo"

fun textoDoFim(motivo: MotivoFim): String = when (motivo) {
    MotivoFim.CANCELADO -> "O encontro foi cancelado"
    MotivoFim.EXPIROU -> "Ninguém entrou a tempo e a proposta expirou"
    MotivoFim.NINGUEM_CHEGOU -> "Ninguém chegou ao ponto a tempo"
    MotivoFim.CHEGARAM -> "Todo mundo chegou ao ponto. Bom rolê!"
    MotivoFim.SAIU -> "Você saiu do encontro"
    MotivoFim.OUTRO -> "O encontro terminou"
}

/** Moto de quem está no encontro (só com posição); sem rumo, aponta para o norte. A própria pessoa é filtrada antes. */
fun Participante.toRiderFeatureOrNull(): RiderFeature? {
    val la = lat ?: return null
    val lo = lon ?: return null
    return RiderFeature(id = sessao, lat = la, lon = lo, rumo = 0.0, destaque = false, icon = RIDER_ICON)
}
const val TEXTO_ESPERA_ENTRAR = "Aguarde um instante e tente de novo"

// ---- Bloquear e denunciar ----

const val REF_BLOQUEIO = "bloqueio"
const val REF_DENUNCIA = "denuncia"
const val REF_AMIGO = "amigo"
const val TEXTO_CONVITE_ENVIADO = "Convite de amizade enviado"
const val TEXTO_AMIGOS_AGORA = "Vocês agora são amigos"
const val TEXTO_SUSPENSO = "Recurso indisponível por denúncias. Fale com o dono do Rumo."
const val TEXTO_DENUNCIA_ENVIADA = "Denúncia enviada"
const val TEXTO_CONFIRMAR_BLOQUEIO = "Você não verá mais essa pessoa e ela não verá você"

val MotivoDenuncia.rotulo: String
    get() = when (this) {
        MotivoDenuncia.INCOMODO -> "Incômodo"
        MotivoDenuncia.COMPORTAMENTO -> "Comportamento"
        MotivoDenuncia.FALSO -> "Perfil falso"
        MotivoDenuncia.OUTRO -> "Outro"
    }

/** Aviso curto para um erro de `bloquear`/`denunciar` ([ref] = "bloqueio" ou "denuncia"). */
fun textoDaModeracao(codigo: String, restanteMs: Long?, ref: String): String {
    val acao = when (ref) {
        REF_BLOQUEIO -> "bloquear"
        REF_AMIGO -> "marcar"
        else -> "denunciar"
    }
    return when (codigo) {
        "inexistente" -> "Não foi possível encontrar essa pessoa. Ela pode ter saído"
        "limite" -> when (ref) {
            REF_BLOQUEIO -> "Você atingiu o limite de pessoas bloqueadas"
            REF_AMIGO -> "Você já marcou muitas pessoas. Tente de novo mais tarde"
            else -> "Muitas denúncias seguidas. Tente de novo mais tarde"
        }
        "espera" -> if (restanteMs != null && restanteMs >= 60_000L) {
            "Espere ${(restanteMs + 59_999L) / 60_000L} min para $acao de novo"
        } else {
            "Espere um instante para $acao de novo"
        }
        "sessao_propria" -> "Essa é a sua própria sessão"
        else -> "Não foi possível $acao agora. Tente de novo daqui a pouco"
    }
}
