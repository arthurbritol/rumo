package app.hunter.app.veiculo

import app.hunter.maintenance.data.MaintenanceItemEntity
import app.hunter.model.MaintenanceMath
import app.hunter.model.TipoVeiculo
import app.hunter.nav.RouteProfile
import app.hunter.nav.TipoDeRota

/** Tipo de rota do veículo; sem veículo ativo vale a moto, como sempre. */
fun TipoVeiculo?.paraRota(): TipoDeRota = if (this == TipoVeiculo.CARRO) TipoDeRota.CARRO else TipoDeRota.MOTO

/** Perfil da prévia ao trocar de veículo: o escolhido se o tipo novo o tem; senão "Mais rápida". */
fun perfilAoTrocarDeTipo(perfil: RouteProfile, tipoNovo: TipoDeRota): RouteProfile = RouteProfile.valido(perfil, tipoNovo)

/** Itens de manutenção iniciais do veículo, do conjunto do tipo (sem id: o banco gera). */
fun itensDeManutencaoPadrao(veiculoId: String, tipo: TipoVeiculo): List<MaintenanceItemEntity> =
    MaintenanceMath.itensPadrao(tipo).map { (nome, km, dias) ->
        MaintenanceItemEntity(name = nome, intervalKm = km, intervalDays = dias, veiculoId = veiculoId)
    }
