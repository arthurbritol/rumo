package app.hunter.app.veiculo

import androidx.compose.runtime.staticCompositionLocalOf
import app.hunter.model.TipoVeiculo

/**
 * Termos e frases que dependem do tipo do veículo ativo (moto ou carro), puros e testáveis. Sem veículo
 * ([TipoVeiculo] nulo) vale a moto, como sempre foi. As frases com concordância (a moto / o carro) são funções
 * próprias, nunca concatenação de termos.
 */
class TextosDoVeiculo(val tipo: TipoVeiculo) {
    private val carro get() = tipo == TipoVeiculo.CARRO

    /** "moto" / "carro". */
    val veiculo: String get() = if (carro) "carro" else "moto"

    /** "motociclista" / "motorista". */
    val pessoa: String get() = if (carro) "motorista" else "motociclista"

    /** "motociclistas" / "motoristas". */
    val pessoas: String get() = pessoa + "s"

    /** "sua moto" / "seu carro". */
    val seu: String get() = if (carro) "seu carro" else "sua moto"

    /** "da moto" / "do carro". */
    val daDo: String get() = if (carro) "do carro" else "da moto"

    /** "Minha moto" / "Meu carro" (apelido padrão). */
    val minha: String get() = if (carro) "Meu carro" else "Minha moto"

    /** "Moto" / "Carro" (rótulo do tipo). */
    val rotuloDoTipo: String get() = if (carro) "Carro" else "Moto"

    /** Nome padrão de quem não digitou apelido nem nome: "Motorista" / "Motociclista". */
    val nomePadrao: String get() = pessoa.replaceFirstChar { it.uppercase() }

    /** Prévia de rota: "Calculando rota para moto…" / "Calculando rota para carro…". */
    val calculandoRota: String get() = "Calculando rota para $veiculo…"

    /** Atalho de busca para o serviço mecânico. */
    val atalhoMecanica: String get() = if (carro) "Oficina mecânica" else "Mecânica de moto"

    /** Título do lembrete: "Manutenção da moto" / "Manutenção do carro" (com a contagem de itens quando mais de um). */
    fun tituloDoLembrete(itens: Int): String =
        if (itens == 1) "Manutenção $daDo" else "Manutenção $daDo ($itens itens)"

    /** "O hodômetro e revisões da moto são ajustados na Garagem." */
    val hodometroNaGaragem: String get() = "O hodômetro e as revisões $daDo são ajustados na Garagem."

    /** "O km da moto se ajusta na Garagem." */
    val kmNaGaragem: String get() = "O km $daDo se ajusta na Garagem."

    companion object {
        val MOTO = TextosDoVeiculo(TipoVeiculo.MOTO)
        val CARRO = TextosDoVeiculo(TipoVeiculo.CARRO)

        /** Sem veículo (nulo) vale a moto. */
        fun de(tipo: TipoVeiculo?): TextosDoVeiculo = if (tipo == TipoVeiculo.CARRO) CARRO else MOTO

        /** Nome de exibição padrão: "Motorista" (carro), "Motociclista" (moto) e "Você" quando ainda não há veículo. */
        fun nomePadrao(tipo: TipoVeiculo?): String = if (tipo == null) "Você" else de(tipo).nomePadrao
    }
}

/** Os textos do veículo ativo, providos na raiz do app; as telas leem sem receber parâmetro. */
val LocalTextosDoVeiculo = staticCompositionLocalOf { TextosDoVeiculo.MOTO }
