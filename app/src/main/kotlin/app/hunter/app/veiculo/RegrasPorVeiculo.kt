package app.hunter.app.veiculo

import app.hunter.model.TipoVeiculo

/** Rolê por perto vale para moto e carro, cada um na sua Celula do servidor (versão B). */
const val ROLE_PARA_CARRO = true

/** O Pod (Bluetooth) só vale para moto; sem veículo ativo vale como hoje (permitido). */
fun podPermitido(tipo: TipoVeiculo?): Boolean = tipo != TipoVeiculo.CARRO

/** O rolê por perto vale para moto (e sem veículo); para carro só quando [ROLE_PARA_CARRO]. */
fun rolePermitido(tipo: TipoVeiculo?): Boolean = tipo != TipoVeiculo.CARRO || ROLE_PARA_CARRO

/** Texto curto quando a remoção está desabilitada (o último veículo não sai). */
const val MSG_MANTENHA_UM_VEICULO = "Mantenha ao menos um veículo"

/** Só se remove um veículo se sobra outro: sem veículo ativo as ações de dados (abastecer, hodômetro) ficariam sem destino. */
fun podeRemoverVeiculo(qtdVeiculos: Int): Boolean = qtdVeiculos > 1

/** A Garagem só mostra os dados (hodômetro, abastecimento, manutenção) com veículo ativo; sem ele, o estado vazio com "Adicionar veículo". */
fun garagemTemDados(temVeiculoAtivo: Boolean): Boolean = temVeiculoAtivo
