package app.hunter.app.nearby

import app.hunter.nearby.ClientMessage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** O que o [NearbyViewModel] precisa do cliente de rede; [NearbyClient] implementa, os testes usam um falso. */
interface NearbyLink {
    val events: SharedFlow<NearbyEvent>
    val status: StateFlow<NearbyStatus>

    /** Emite o id do quadrado cada vez que a conexão dele abre (inclusive reconexões). */
    val opened: SharedFlow<String>

    /** Emite o id do encontro cada vez que a conexão extra dele abre (inclusive reconexões). */
    val openedEncounter: SharedFlow<String>

    /** Passa a manter exatamente uma conexão por quadrado de [cells]. */
    fun connect(cells: Set<String>)

    /** [cell] nulo: todas as conexões de quadrado (posição, procurar, parar). */
    fun send(msg: ClientMessage, cell: String? = null)

    /** Conexão extra ao quadrado [cell] do encontro [encId]; repetir a mesma não abre outra. */
    fun connectEncounter(cell: String, encId: String)

    fun disconnectEncounter()

    /** Manda a mensagem só pela conexão extra do encontro. */
    fun sendEncounter(msg: ClientMessage)

    /** Avisa que há encontro em andamento (ou aguardando resposta): o cliente adia a renovação do token. */
    fun setEncounterActive(active: Boolean)

    fun disconnect()
}
