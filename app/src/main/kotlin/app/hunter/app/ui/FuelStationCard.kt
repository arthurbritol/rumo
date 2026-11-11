package app.hunter.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hunter.app.R
import app.hunter.app.fuel.PriceSource
import app.hunter.app.fuel.ResolvedFuelStation
import app.hunter.model.FuelParityMath
import java.util.Locale

private val PtBrLocale: Locale = Locale.forLanguageTag("pt-BR")

/**
 * Informações resolvidas de paridade Etanol vs. Gasolina para exibição na UI.
 */
data class FuelParityUiInfo(
    val ratio: Double,
    val description: String,
    val isEtanolAdvantageous: Boolean,
)

/**
 * Formata o valor de combustível no padrão monetário brasileiro (ex: "R$ 5,79").
 * Retorna "-" para valores ausentes, não positivos ou não finitos.
 */
fun formatFuelPrice(price: Double?): String {
    if (price == null || !price.isFinite() || price <= 0.0) return "-"
    return String.format(PtBrLocale, "R$ %.2f", price)
}

/**
 * Formata a procedência do preço e a data de referência oficial da ANP.
 */
fun formatPriceSourceNotice(source: PriceSource, dataRef: String): String {
    val cleanDate = dataRef.trim()
    return when (source) {
        PriceSource.POSTO_ANP -> "Pesquisa oficial ANP · Coletado em $cleanDate"
        PriceSource.MEDIA_MUNICIPAL -> "Média estimada da cidade ($cleanDate)"
        PriceSource.MEDIA_ESTADUAL -> "Média estadual estimada ($cleanDate)"
        PriceSource.SEM_PRECO -> "A ANP não pesquisou este posto · toque em Informar preços"
    }
}

/**
 * Calcula a razão de paridade entre Etanol e Gasolina e determina qual combustível compensa mais.
 * Retorna `null` se qualquer um dos preços for nulo, não positivo ou infinito.
 */
fun resolveFuelParity(precoEtanol: Double?, precoGasolina: Double?): FuelParityUiInfo? {
    if (precoEtanol == null || precoGasolina == null) return null
    val ratio = FuelParityMath.calculateRatio(precoEtanol, precoGasolina) ?: return null
    val isAdvantageous = FuelParityMath.isEtanolAdvantageous(ratio)
    val description = FuelParityMath.formatParityDescription(ratio)
    return FuelParityUiInfo(
        ratio = ratio,
        description = description,
        isEtanolAdvantageous = isAdvantageous,
    )
}

/**
 * Formata a distância até o posto em metros (< 1000m) ou quilômetros com 1 casa decimal.
 */
fun formatFuelStationDistance(distanceM: Double?): String? {
    if (distanceM == null || !distanceM.isFinite() || distanceM < 0.0) return null
    return if (distanceM < 1000.0) {
        String.format(PtBrLocale, "%.0f m", distanceM)
    } else {
        String.format(PtBrLocale, "%.1f km", distanceM / 1000.0)
    }
}

/**
 * Formata o subtítulo do posto combinando endereço, bairro e distância do usuário.
 */
fun formatStationSubtitle(distanceM: Double?, endereco: String, bairro: String): String {
    val cleanEndereco = endereco.trim()
    val cleanBairro = bairro.trim()
    val addressPart = when {
        cleanEndereco.isNotBlank() && cleanBairro.isNotBlank() -> "$cleanEndereco · $cleanBairro"
        cleanEndereco.isNotBlank() -> cleanEndereco
        cleanBairro.isNotBlank() -> cleanBairro
        else -> ""
    }
    val distLabel = formatFuelStationDistance(distanceM)?.let { "a $it de você" }

    return when {
        addressPart.isNotBlank() && distLabel != null -> "$addressPart · $distLabel"
        addressPart.isNotBlank() -> addressPart
        distLabel != null -> distLabel
        else -> ""
    }
}

/**
 * Pílula de status semântica com suporte a cor arbitrária Novare (ex: Verde para Etanol, Ciano para Gasolina).
 */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(color, CircleShape),
            )
            Text(
                text = text,
                color = color,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Cartão de preço individual para um combustível específico.
 */
@Composable
fun FuelPriceItem(
    label: String,
    price: Double?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatFuelPrice(price),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (price != null && price > 0.0) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                },
                maxLines = 1,
            )
        }
    }
}

/**
 * Grade com os preços dos três combustíveis pesquisados (Gasolina Comum, Aditivada e Etanol).
 */
@Composable
fun FuelPricesGrid(
    station: ResolvedFuelStation,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FuelPriceItem(
            label = "Gasolina Comum",
            price = station.precoGasolina,
            modifier = Modifier.weight(1f),
        )
        FuelPriceItem(
            label = "Gasolina Adit.",
            price = station.precoGasolinaAdit,
            modifier = Modifier.weight(1f),
        )
        FuelPriceItem(
            label = "Etanol",
            price = station.precoEtanol,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Faixa com a gasolina comum em destaque e, abaixo, as demais linhas de preço do posto. */
@Composable
fun FuelPriceHighlight(station: ResolvedFuelStation, modifier: Modifier = Modifier) {
    val green = NovareSuccess
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = green.copy(alpha = 0.15f),
            border = BorderStroke(1.dp, green.copy(alpha = 0.35f)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Gasolina Comum",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatFuelPrice(station.precoGasolina),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (station.precoGasolina != null) green else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        listOf(
            "Gasolina Aditivada" to station.precoGasolinaAdit,
            "Etanol" to station.precoEtanol,
            "Diesel" to station.precoDiesel,
        ).filter { it.second != null || it.first == "Etanol" }.forEach { (label, price) ->
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatFuelPrice(price),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Componente modal/bottom sheet de posto de combustível segundo o Design Novare e Font Awesome 6.
 *
 * Apresenta o cabeçalho com SquircleIcon e badge de bandeira, grade dos combustíveis (GC, GA, ET),
 * termômetro de paridade Etanol x Gasolina (StatusPill), carimbo de procedência ANP
 * e botões de navegação, informe de preços e adição de parada.
 */
@Composable
fun FuelStationCard(
    station: ResolvedFuelStation,
    distanceM: Double?,
    onNavigate: () -> Unit,
    onAddStop: () -> Unit,
    onClose: () -> Unit,
    onEditPrices: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    HCard(modifier = modifier) {
        // Cabeçalho com SquircleIcon, Nome do Posto, Bandeira e botão Fechar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SquircleIcon(
                iconRes = R.drawable.fa_gas_pump,
                backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                tintColor = MaterialTheme.colorScheme.primary,
                contentDescription = "Posto de Combustível",
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = station.nome,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (station.bandeira.isNotBlank()) {
                        RumoBadge(
                            text = station.bandeira,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                val subtitle = formatStationSubtitle(
                    distanceM = distanceM,
                    endereco = station.endereco,
                    bairro = station.bairro,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Fechar",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Gasolina em destaque; etanol, aditivada e diesel em linhas (como no mapa de referência)
        FuelPriceHighlight(station = station)

        // Termômetro de Paridade (se Etanol e Gasolina disponíveis)
        val parityInfo = resolveFuelParity(station.precoEtanol, station.precoGasolina)
        if (parityInfo != null) {
            val pillColor = if (parityInfo.isEtanolAdvantageous) NovareSuccess else NovareInfo
            StatusPill(
                text = parityInfo.description,
                color = pillColor,
            )
        }

        // Procedência e Data de Referência da ANP
        Text(
            text = formatPriceSourceNotice(station.fonte, station.dataRef),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))

        // Botões de Ação Novare
        RumoPrimaryButton(
            text = "Ver rotas",
            onClick = onNavigate,
            leadingIconRes = R.drawable.fa_route,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RumoSecondaryButton(
                text = "Informar preços",
                onClick = onEditPrices,
                leadingIconRes = R.drawable.fa_gas_pump,
                modifier = Modifier.weight(1f),
            )
            RumoSecondaryButton(
                text = "Adicionar parada",
                onClick = onAddStop,
                leadingIconRes = R.drawable.fa_plus,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
