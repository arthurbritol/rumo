package app.hunter.app.ui

import app.hunter.app.ui.onboarding.BikeForm
import app.hunter.app.ui.onboarding.FormError
import app.hunter.app.ui.onboarding.FormField
import app.hunter.sync.UserProfile
import java.time.Year

/**
 * "Editar perfil" (tela de perfil): nome, marca, modelo e ano. Mesmos limites do cadastro da moto e das regras
 * (textos até [BikeForm.MAX_TEXT], ano de [BikeForm.MIN_YEAR] até o atual); aqui marca e modelo são opcionais
 * (dá para "completar depois"). O km/hodômetro continua na Garagem. Kotlin puro (testável na JVM).
 */
class ProfileEditForm(var name: String, var brand: String, var model: String, var year: String) {

    fun validate(currentYear: Int = Year.now().value): List<FormError> = buildList {
        val n = name.trim()
        if (n.isEmpty()) add(FormError(FormField.NAME, "Informe seu nome."))
        else if (n.length > BikeForm.MAX_TEXT) add(FormError(FormField.NAME, "O nome deve ter no máximo ${BikeForm.MAX_TEXT} caracteres."))
        if (brand.trim().length > BikeForm.MAX_TEXT) add(FormError(FormField.BRAND, "A marca deve ter no máximo ${BikeForm.MAX_TEXT} caracteres."))
        if (model.trim().length > BikeForm.MAX_TEXT) add(FormError(FormField.MODEL, "O modelo deve ter no máximo ${BikeForm.MAX_TEXT} caracteres."))
        if (year.isNotBlank()) {
            val y = year.trim().toIntOrNull()
            if (y == null || y < BikeForm.MIN_YEAR || y > currentYear) {
                add(FormError(FormField.YEAR, "O ano deve estar entre ${BikeForm.MIN_YEAR} e $currentYear."))
            }
        }
    }

    /** Só chamar com [validate] vazio. [voice] é preservada (não é editada aqui). */
    fun toProfile(voice: Boolean): UserProfile = UserProfile(
        displayName = name.trim(),
        brand = brand.trim().ifEmpty { null },
        model = model.trim().ifEmpty { null },
        year = year.trim().toIntOrNull(),
        voice = voice,
    )

    companion object {
        /** Começa do perfil atual; sem perfil, do nome da conta ([fallbackName]) e moto vazia. */
        fun from(p: UserProfile?, fallbackName: String): ProfileEditForm = ProfileEditForm(
            name = p?.displayName ?: fallbackName.trim(),
            brand = p?.brand.orEmpty(),
            model = p?.model.orEmpty(),
            year = p?.year?.toString().orEmpty(),
        )
    }
}
