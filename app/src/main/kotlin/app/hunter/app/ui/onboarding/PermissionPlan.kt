package app.hunter.app.ui.onboarding

data class PermStep(val id: String, val permissions: List<String>, val title: String, val reason: String)

/**
 * Quais permissões pedir em cada versão do Android. Nunca inclui localização em segundo plano. O Bluetooth só entra
 * com [comPod] (veículo ativo é moto): carro nunca recebe pedido de Bluetooth.
 */
object PermissionPlan {
    fun steps(sdk: Int, comPod: Boolean = true): List<PermStep> = buildList {
        add(
            PermStep(
                id = "location",
                permissions = listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
                title = "Localização",
                reason = "Mostra você no mapa, traça a rota e grava as viagens.",
            ),
        )
        if (sdk >= 31 && comPod) {
            add(
                PermStep(
                    id = "bluetooth",
                    permissions = listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"),
                    title = "Bluetooth",
                    reason = "Conecta ao Pod, o painel da moto.",
                ),
            )
        }
        if (sdk >= 33) {
            add(
                PermStep(
                    id = "notifications",
                    permissions = listOf("android.permission.POST_NOTIFICATIONS"),
                    title = "Notificações",
                    reason = "Avisa sobre a gravação da viagem e lembretes de manutenção.",
                ),
            )
        }
    }
}
