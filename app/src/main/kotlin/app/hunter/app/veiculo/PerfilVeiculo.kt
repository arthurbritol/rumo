package app.hunter.app.veiculo

import app.hunter.app.ui.ProfileEditForm
import app.hunter.app.ui.onboarding.BikeForm
import app.hunter.model.TipoVeiculo
import app.hunter.model.Veiculo
import app.hunter.sync.UserProfile

/**
 * Cartão "Dados do veículo" do Perfil: a edição de marca, modelo e ano vale para o VEÍCULO ATIVO (a Garagem lê dele).
 * Só moto (ou sem veículo ativo) também grava no perfil, que é o rótulo social/compatibilidade; carro nunca mexe
 * em marca, modelo e ano do perfil. Valor vazio nunca apaga o que já existe. Kotlin puro.
 */
object PerfilVeiculo {
    data class Dados(val marca: String?, val modelo: String?, val ano: String?)

    /** O que o cartão mostra: o veículo ativo, se houver; senão o perfil. */
    fun dadosExibidos(ativo: Veiculo?, perfil: UserProfile?): Dados =
        if (ativo != null) {
            Dados(ativo.marca.trim().ifEmpty { null }, ativo.modelo.trim().ifEmpty { null }, ativo.ano?.toString())
        } else {
            Dados(perfil?.brand?.trim()?.ifEmpty { null }, perfil?.model?.trim()?.ifEmpty { null }, perfil?.year?.toString())
        }

    /** Formulário de edição: nome do perfil (ou da conta) e marca/modelo/ano do veículo ativo (senão do perfil). */
    fun formDe(perfil: UserProfile?, nomeDaConta: String, ativo: Veiculo?): ProfileEditForm {
        val base = ProfileEditForm.from(perfil, nomeDaConta)
        if (ativo != null) {
            base.brand = ativo.marca
            base.model = ativo.modelo
            base.year = ativo.ano?.toString().orEmpty()
        }
        return base
    }

    /** [veiculo]: o veículo ativo editado (nulo se não há ativo); [perfil]: o perfil a gravar. */
    data class Resultado(val veiculo: Veiculo?, val perfil: UserProfile)

    /** Só chamar com [ProfileEditForm.validate] vazio. */
    fun aplicar(form: ProfileEditForm, ativo: Veiculo?, perfilAtual: UserProfile?, voice: Boolean): Resultado {
        val marca = form.brand.trim().ifEmpty { null }
        val modelo = form.model.trim().ifEmpty { null }
        val ano = form.year.trim().toIntOrNull()
        val veiculo = ativo?.copy(
            marca = marca ?: ativo.marca,
            modelo = modelo ?: ativo.modelo,
            ano = ano ?: ativo.ano,
        )
        val nome = form.name.trim()
        val perfil = if (ativo != null && ativo.tipo == TipoVeiculo.CARRO) {
            // Carro: marca, modelo e ano do perfil ficam como estavam.
            UserProfile(nome, perfilAtual?.brand, perfilAtual?.model, perfilAtual?.year, voice = voice)
        } else {
            UserProfile(
                displayName = nome,
                brand = marca ?: perfilAtual?.brand,
                model = modelo ?: perfilAtual?.model,
                // O perfil só aceita 1950 em diante: ano de veículo mais antigo não vai para o perfil (não é erro).
                year = ano?.takeIf { it >= BikeForm.MIN_YEAR } ?: perfilAtual?.year,
                voice = voice,
            )
        }
        return Resultado(veiculo, perfil)
    }
}
