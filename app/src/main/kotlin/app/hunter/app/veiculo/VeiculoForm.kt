package app.hunter.app.veiculo

import app.hunter.app.ui.onboarding.BikeForm
import app.hunter.app.ui.onboarding.FormError
import app.hunter.app.ui.onboarding.FormField
import app.hunter.model.TipoVeiculo
import app.hunter.model.Veiculo
import app.hunter.sync.BikeBrand
import app.hunter.sync.BikeCatalog
import app.hunter.sync.CarCatalog
import app.hunter.sync.UserProfile

/** Catálogo de marcas e modelos do tipo de veículo (o usuário sempre pode digitar um texto livre). */
fun catalogoDoTipo(tipo: TipoVeiculo): List<BikeBrand> = when (tipo) {
    TipoVeiculo.MOTO -> BikeCatalog.brands
    TipoVeiculo.CARRO -> CarCatalog.brands
}

/** Modelos da [marca] no catálogo do [tipo]; marca fora da lista: vazio. */
fun modelosDoTipo(tipo: TipoVeiculo, marca: String): List<String> =
    catalogoDoTipo(tipo).firstOrNull { it.name == marca }?.models.orEmpty()

/**
 * "Tem algum veículo" para o onboarding: há veículo ativo (o migrado conta) ou o perfil antigo traz marca e modelo
 * (compatibilidade: o perfil chega antes dos veículos numa conta antiga).
 */
fun temVeiculo(ativo: Veiculo?, perfil: UserProfile?): Boolean =
    ativo != null || (perfil?.let { it.brand != null && it.model != null } == true)

/** Nome curto do veículo para listas: apelido, senão "marca modelo", senão o tipo. */
fun Veiculo.nomeExibicao(): String =
    apelido.trim().ifEmpty { listOf(marca, modelo).filter { it.isNotBlank() }.joinToString(" ").trim() }
        .ifEmpty { TextosDoVeiculo.de(tipo).rotuloDoTipo }

/** Segunda linha da lista: "marca modelo · ano", sem repetir o nome quando ele já é "marca modelo". */
fun Veiculo.detalhe(): String {
    val mm = listOf(marca, modelo).filter { it.isNotBlank() }.joinToString(" ")
    val partes = listOfNotNull(mm.takeIf { it.isNotEmpty() && it != nomeExibicao() }, ano?.toString())
    return partes.joinToString(" · ")
}

/** Regras de cadastro iguais às das regras do Firestore (`veiculoValido`). Kotlin puro. */
object RegrasVeiculo {
    const val TEXTO_MAX = 60
    const val ANO_MIN = 1900
    const val ANO_MAX = 2100
    const val TANQUE_MAX = 200.0
    const val TANQUE_PADRAO_MOTO = 13.0
    const val TANQUE_PADRAO_CARRO = 45.0
    private val NUMERO = Regex("""\d+([.,]\d+)?""")

    fun tanquePadrao(tipo: TipoVeiculo): Double =
        if (tipo == TipoVeiculo.CARRO) TANQUE_PADRAO_CARRO else TANQUE_PADRAO_MOTO

    /** Aceita vírgula ou ponto decimal. */
    fun parseNumero(text: String): Double? =
        text.trim().takeIf { NUMERO.matches(it) }?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }

    fun formatarLitros(l: Double): String =
        if (l == Math.floor(l)) l.toInt().toString() else l.toString().replace('.', ',')

    fun erroAno(text: String): String? {
        if (text.isBlank()) return null
        val y = text.trim().toIntOrNull()
        return if (y == null || y < ANO_MIN || y > ANO_MAX) "O ano deve estar entre $ANO_MIN e $ANO_MAX." else null
    }

    fun erroTanque(text: String, tipo: TipoVeiculo): String? {
        if (text.isBlank()) return "Informe a capacidade do tanque."
        val t = parseNumero(text)
        return if (t == null || t <= 0 || t > TANQUE_MAX) {
            "Tanque inválido. Informe os litros (maior que 0 e até ${TANQUE_MAX.toInt()}), por exemplo ${formatarLitros(tanquePadrao(tipo))}."
        } else null
    }

    fun erroMarca(text: String): String? = when {
        text.isBlank() -> "Escolha a marca."
        text.trim().length > TEXTO_MAX -> "A marca deve ter no máximo $TEXTO_MAX caracteres."
        else -> null
    }

    fun erroModelo(text: String): String? = when {
        text.isBlank() -> "Informe o modelo."
        text.trim().length > TEXTO_MAX -> "O modelo deve ter no máximo $TEXTO_MAX caracteres."
        else -> null
    }

    fun erroApelido(text: String): String? =
        if (text.trim().length > TEXTO_MAX) "O apelido deve ter no máximo $TEXTO_MAX caracteres." else null
}

/**
 * Formulário de veículo da Garagem (adicionar e editar). Os campos são o texto digitado; [validar] aplica as regras
 * de [RegrasVeiculo] e [paraVeiculo] monta o [Veiculo]. [exigirMarcaModelo] falso só para editar o veículo migrado
 * que ainda não tem marca nem modelo.
 */
class VeiculoForm(tipo: TipoVeiculo = TipoVeiculo.MOTO, val exigirMarcaModelo: Boolean = true) {
    var tipo: TipoVeiculo = tipo
        private set
    var apelido: String = ""
    var marca: String = ""
    var modelo: String = ""
    var ano: String = ""
    var tanque: String = RegrasVeiculo.formatarLitros(RegrasVeiculo.tanquePadrao(tipo))
        private set

    private var tanqueEditado = false

    /** Troca o tipo: marca e modelo (do outro catálogo) são limpos e o tanque volta ao padrão do tipo, se não foi editado. */
    fun escolherTipo(novo: TipoVeiculo) {
        if (novo == tipo) return
        tipo = novo
        marca = ""
        modelo = ""
        if (!tanqueEditado) tanque = RegrasVeiculo.formatarLitros(RegrasVeiculo.tanquePadrao(novo))
    }

    /** Escolha de marca e modelo: sugere o tanque da moto pelo modelo (carro: padrão), a menos que o usuário já tenha editado. */
    fun selecionar(marca: String, modelo: String) {
        this.marca = marca
        this.modelo = modelo
        if (!tanqueEditado) {
            tanque = RegrasVeiculo.formatarLitros(
                if (tipo == TipoVeiculo.MOTO) BikeForm.defaultTankLiters(marca, modelo) else RegrasVeiculo.tanquePadrao(tipo),
            )
        }
    }

    fun editarTanque(text: String) {
        tanqueEditado = true
        tanque = text
    }

    fun validar(): List<FormError> = buildList {
        RegrasVeiculo.erroApelido(apelido)?.let { add(FormError(FormField.NICKNAME, it)) }
        if (exigirMarcaModelo) {
            RegrasVeiculo.erroMarca(marca)?.let { add(FormError(FormField.BRAND, it)) }
            RegrasVeiculo.erroModelo(modelo)?.let { add(FormError(FormField.MODEL, it)) }
        } else {
            if (marca.trim().length > RegrasVeiculo.TEXTO_MAX) add(FormError(FormField.BRAND, "A marca deve ter no máximo ${RegrasVeiculo.TEXTO_MAX} caracteres."))
            if (modelo.trim().length > RegrasVeiculo.TEXTO_MAX) add(FormError(FormField.MODEL, "O modelo deve ter no máximo ${RegrasVeiculo.TEXTO_MAX} caracteres."))
        }
        RegrasVeiculo.erroAno(ano)?.let { add(FormError(FormField.YEAR, it)) }
        RegrasVeiculo.erroTanque(tanque, tipo)?.let { add(FormError(FormField.TANK, it)) }
    }

    /** Só chamar depois de [validar] vazio. [base] (edição) mantém consumo padrão e remoção. */
    fun paraVeiculo(id: String, criadoEm: Long, base: Veiculo? = null): Veiculo = Veiculo(
        id = id, tipo = tipo, apelido = apelido.trim(), marca = marca.trim(), modelo = modelo.trim(),
        ano = ano.trim().toIntOrNull(),
        tanqueLitros = RegrasVeiculo.parseNumero(tanque) ?: RegrasVeiculo.tanquePadrao(tipo),
        consumoPadraoKmL = base?.consumoPadraoKmL, criadoEm = criadoEm, removidoEm = base?.removidoEm,
    )

    companion object {
        /** Formulário preenchido com o veículo existente (o tanque conta como editado: não é sobrescrito por sugestão). */
        fun de(v: Veiculo): VeiculoForm = VeiculoForm(v.tipo, exigirMarcaModelo = v.marca.isNotBlank() || v.modelo.isNotBlank()).apply {
            apelido = v.apelido
            marca = v.marca
            modelo = v.modelo
            ano = v.ano?.toString().orEmpty()
            editarTanque(RegrasVeiculo.formatarLitros(v.tanqueLitros))
        }
    }
}
