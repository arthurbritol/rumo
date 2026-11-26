package app.hunter.app.veiculo

import app.hunter.model.TipoVeiculo

/**
 * Rolê por perto só existe para moto na versão A. T8: ligar para carro quando o rolê separado por tipo existir
 * (basta virar esta constante; nenhuma outra condição deve citar o tipo).
 */
// T8: ligar para carro quando o rolê separado por tipo existir
const val ROLE_PARA_CARRO = false

/** O Pod (Bluetooth) só vale para moto; sem veículo ativo vale como hoje (permitido). */
fun podPermitido(tipo: TipoVeiculo?): Boolean = tipo != TipoVeiculo.CARRO

/** O rolê por perto vale para moto (e sem veículo); para carro só quando [ROLE_PARA_CARRO]. */
fun rolePermitido(tipo: TipoVeiculo?): Boolean = tipo != TipoVeiculo.CARRO || ROLE_PARA_CARRO
