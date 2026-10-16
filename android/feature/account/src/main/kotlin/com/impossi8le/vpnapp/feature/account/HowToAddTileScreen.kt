package com.impossi8le.vpnapp.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.impossi8le.vpnapp.core.ui.Tone
import com.impossi8le.vpnapp.core.ui.VpnCard
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.core.ui.VpnNotice
import com.impossi8le.vpnapp.core.ui.VpnRow

const val TILE_HOW_TO_BACK_TAG = "tile_how_to_back"

/** Шаг инструкции: заголовок и пояснение. Тот же вид, что у строк аккаунта. */
data class TileHowToStep(val title: String, val sub: String)

/**
 * Вводная строка экрана.
 *
 * Главное, что она обязана сказать, — значок НЕ появился на панели сам. Иначе
 * пользователь прочтёт инструкцию как «плитка уже добавлена» и будет искать то,
 * чего на панели нет.
 */
const val TILE_HOW_TO_INTRO =
    "Приложение умеет подключаться одним тапом из шторки «Быстрые настройки». " +
        "Значок не появляется на панели сам — его нужно добавить один раз вручную, " +
        "по шагам ниже. Пока этого не сделано, плитки на панели нет."

/**
 * Шаги добавления плитки.
 *
 * Формулировки обобщённые и намеренно не дословные: расположение и названия
 * пунктов отличаются между прошивками (Pixel, Samsung, Xiaomi и другие), и
 * обещать точное слово в меню значило бы соврать на части устройств. Назван
 * только общий путь.
 */
val TILE_HOW_TO_STEPS: List<TileHowToStep> = listOf(
    TileHowToStep(
        "1. Откройте «Быстрые настройки»",
        "Свайп двумя пальцами сверху вниз — откроется полная шторка с плитками",
    ),
    TileHowToStep(
        "2. Измените набор плиток",
        "Найдите пункт «изменить» или значок карандаша — надпись отличается " +
            "на разных прошивках",
    ),
    TileHowToStep(
        "3. Найдите приложение",
        "В списке доступных плиток найдите это приложение по названию " +
            "или слову VPN",
    ),
    TileHowToStep(
        "4. Перетащите на панель",
        "Плитка встанет среди остальных и будет подключать VPN одним тапом",
    ),
)

/**
 * Честная оговорка к инструкции.
 *
 * Здесь две вещи, о которых лучше сказать заранее, чем оставить пользователя
 * гадать, почему «не так, как на картинке»:
 *  - названия пунктов меню зависят от прошивки;
 *  - первый тап по плитке может открыть приложение — если разрешение на VPN ещё
 *    не дано, системный диалог покажет только само приложение. Это ровно то
 *    поведение, что описано в `VpnTileService`.
 */
const val TILE_HOW_TO_NOTE =
    "Название и расположение пунктов может немного отличаться в вашей версии " +
        "Android. Ищите слова «изменить», «плитки» и VPN. Если разрешение на VPN " +
        "ещё не дано, первый тап по плитке откроет приложение — там система " +
        "спросит разрешение."

/**
 * Экран «Как добавить плитку» (по образцу «Как включить VPN»).
 *
 * Свой экран, а не скрытый переход в системные настройки: путь к настройке
 * плиток на разных прошивках разный и между версиями ломается, поэтому здесь —
 * статичная читаемая инструкция без единого намерения наружу.
 *
 * **Про удобство, а не про защиту.** Экран объясняет, как добавить значок
 * быстрого доступа. Ничего «зелёного» и никаких слов о защите тут нет и быть не
 * должно (§6): плитка говорит только о состоянии туннеля, и подсказка о ней —
 * ровно про удобство.
 */
@Composable
fun HowToAddTileScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VpnColors.Void)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(top = 8.dp, bottom = 24.dp),
    ) {
        AccountNavBar(title = "Как добавить плитку", onBack = onBack, testTag = TILE_HOW_TO_BACK_TAG)

        Text(
            text = TILE_HOW_TO_INTRO,
            color = VpnColors.TextSecondary,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        VpnCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                TILE_HOW_TO_STEPS.forEachIndexed { index, step ->
                    if (index > 0) StepDivider()
                    VpnRow(key = step.title, sub = step.sub)
                }
            }
        }

        Box(modifier = Modifier.padding(top = 12.dp)) {
            VpnNotice(text = TILE_HOW_TO_NOTE, tone = Tone.Neutral)
        }
    }
}

/** Разделитель между шагами: волосяная линия, как между строками в макете. */
@Composable
private fun StepDivider() {
    HorizontalDivider(color = VpnColors.Hairline, thickness = 0.5.dp)
}
