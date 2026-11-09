package app.hunter.app.ui.onboarding

import app.hunter.maintenance.Odometer
import app.hunter.sync.OdometerDoc
import app.hunter.sync.UserProfile
import java.time.Year

enum class FormField { BRAND, MODEL, YEAR, ODOMETER, TANK }

data class FormError(val field: FormField, val message: String)

/**
 * Cadastro da moto. [short]: sem conta, só km e tanque. Os campos são o texto digitado; a conversão
 * e a validação acontecem em [validate], [toProfile] e [toOdometer]. Kotlin puro (testável na JVM).
 */
class BikeForm(val short: Boolean) {
    var brand: String = ""
    var model: String = ""
    var year: String = ""
    var odometerKm: String = ""
    var tankLiters: String = formatLiters(DEFAULT_TANK_L)
        private set

    private var tankEdited = false

    /** Escolha de marca e modelo: sugere o tanque do modelo, a menos que o usuário já tenha editado. */
    fun selectBike(brand: String, model: String) {
        this.brand = brand
        this.model = model
        if (!tankEdited) tankLiters = formatLiters(defaultTankLiters(brand, model))
    }

    /** Edição manual do tanque: passa a valer sobre a sugestão do modelo. */
    fun editTank(text: String) {
        tankEdited = true
        tankLiters = text
    }

    fun validate(currentYear: Int = Year.now().value): List<FormError> = buildList {
        if (!short) {
            if (brand.isBlank()) add(FormError(FormField.BRAND, "Escolha a marca."))
            if (model.isBlank()) add(FormError(FormField.MODEL, "Informe o modelo."))
            if (year.isNotBlank()) {
                val y = year.trim().toIntOrNull()
                if (y == null || y < MIN_YEAR || y > currentYear) {
                    add(FormError(FormField.YEAR, "O ano deve estar entre $MIN_YEAR e $currentYear."))
                }
            }
        }
        val km = parseNumber(odometerKm)
        if (odometerKm.isBlank()) add(FormError(FormField.ODOMETER, "Informe os km do painel."))
        else if (km == null || km > MAX_KM) add(FormError(FormField.ODOMETER, "Km inválido. Use um número de 0 a 2.000.000."))
        val tank = parseNumber(tankLiters)
        if (tankLiters.isBlank()) add(FormError(FormField.TANK, "Informe a capacidade do tanque."))
        else if (tank == null || tank <= 0 || tank > MAX_TANK_L) add(FormError(FormField.TANK, "Tanque inválido. Informe os litros, por exemplo 13."))
    }

    /** No cadastro curto o perfil não leva marca, modelo nem ano. */
    fun toProfile(name: String): UserProfile = UserProfile(
        displayName = name,
        brand = if (short) null else brand.trim().ifBlank { null },
        model = if (short) null else model.trim().ifBlank { null },
        year = if (short) null else year.trim().toIntOrNull(),
    )

    /**
     * O hodômetro guarda um deslocamento: km do painel menos as viagens já gravadas
     * ([tripsDistanceM], obrigatório: sem ele o hodômetro exibido sairia errado). Só chamar depois
     * de [validate] vazio.
     */
    fun toOdometer(nowMs: Long, tripsDistanceM: Double): OdometerDoc = OdometerDoc(
        offsetKm = Odometer.offsetFor(parseNumber(odometerKm) ?: 0.0, tripsDistanceM),
        tankLiters = parseNumber(tankLiters) ?: DEFAULT_TANK_L,
        updatedAtMs = nowMs,
    )

    companion object {
        const val MIN_YEAR = 1950
        const val DEFAULT_TANK_L = 13.0
        private const val MAX_TANK_L = 60.0
        private const val MAX_KM = 2_000_000.0
        private val NUMBER = Regex("""\d+([.,]\d+)?""")

        /** Capacidades aproximadas do tanque por modelo (L); o usuário pode ajustar. Sem o modelo na tabela, 13 L. */
        private val TANKS: Map<String, Double> = mapOf(
            "Hunter 350" to 13.0, "Meteor 350" to 15.0, "Classic 350" to 13.0, "Bullet 350" to 13.5,
            "Himalayan" to 15.0, "Scram 411" to 15.0, "Interceptor 650" to 13.7,
            "Continental GT 650" to 12.5, "Super Meteor 650" to 15.7,
            "Fazer 250" to 19.0, "Lander 250" to 16.0, "XTZ 150" to 12.0, "MT-03" to 14.0, "MT-07" to 14.0,
            "MT-09" to 14.0, "Ténéré 700" to 16.0, "XJ6" to 17.0,
            "CB 300F Twister" to 16.3, "CB 500F" to 17.7, "CB 650R" to 15.4, "CG 160" to 14.0, "XRE 300" to 13.0,
            "NC 750X" to 14.1, "Africa Twin" to 18.8, "Bros 160" to 12.0, "PCX 160" to 8.1,
            "Ninja 400" to 14.0, "Z400" to 14.0, "Z650" to 15.0, "Z900" to 17.0, "Versys 650" to 21.0, "Vulcan S" to 14.0,
            "Speed 400" to 13.0, "Scrambler 400 X" to 13.0, "Street Triple" to 17.4, "Trident 660" to 14.0,
            "Tiger 660" to 17.2, "Bonneville T120" to 14.5, "Speed Twin" to 14.5,
            "G 310 R" to 11.0, "G 310 GS" to 11.5, "F 750 GS" to 15.0, "F 850 GS" to 15.0, "R 1250 GS" to 20.0, "R nineT" to 18.0,
            "Duke 200" to 13.4, "Duke 390" to 13.4, "390 Adventure" to 14.5, "790 Duke" to 14.0, "890 Adventure" to 20.0,
            "Apache RTR 160" to 12.0, "Apache RTR 200" to 12.0, "Ronin" to 14.0, "Jupiter" to 5.1,
            "Dominar 160" to 13.0, "Dominar 400" to 13.0, "Pulsar NS200" to 12.0, "Pulsar 220F" to 15.0,
            "Iron 883" to 12.5, "Sportster S" to 11.8, "Nightster" to 11.7, "Street Glide" to 22.7, "Fat Boy" to 18.9,
        )

        fun defaultTankLiters(brand: String, model: String): Double = TANKS[model] ?: DEFAULT_TANK_L

        /** Aceita vírgula ou ponto decimal. */
        private fun parseNumber(text: String): Double? =
            text.trim().takeIf { NUMBER.matches(it) }?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }

        fun formatLiters(l: Double): String =
            if (l == Math.floor(l)) l.toInt().toString() else l.toString().replace('.', ',')
    }
}
